package com.dch.smartrecruters.state.jdbc;

import com.dch.smartrecruters.state.JobProgress;
import com.dch.smartrecruters.state.TenantMigrationJob;
import com.dch.smartrecruters.state.TenantMigrationJobRepository;
import com.dch.smartrecruters.state.TenantMigrationJobStatus;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public class JdbcTenantMigrationJobRepository implements TenantMigrationJobRepository {

    private static final int MAX_ERROR_LENGTH = 1000;

    private static final String COLUMNS = """
            job_id, tenant_id, status, page_size, next_page,
            processed_count, succeeded_count, skipped_count, failed_count,
            last_error, created_at, updated_at
            """;

    private final DataSource dataSource;
    private final Duration leaseTimeout;

    public JdbcTenantMigrationJobRepository(DataSource dataSource, Duration leaseTimeout) {
        if (leaseTimeout == null || leaseTimeout.isNegative() || leaseTimeout.isZero()) {
            throw new IllegalArgumentException("leaseTimeout must be positive");
        }
        this.dataSource = dataSource;
        this.leaseTimeout = leaseTimeout;
    }

    /**
     * The partial unique index allows only one unfinished job per tenant, so concurrent
     * starts for the same tenant end up with the same job. The lookup is retried because the
     * conflicting job may finish between the INSERT and the SELECT.
     */
    @Override
    public TenantMigrationJob createOrGetUnfinished(String tenantId, int pageSize) {
        String insert = """
                INSERT INTO tenant_migration_job (job_id, tenant_id, status, page_size)
                VALUES (?, ?, 'PENDING', ?)
                ON CONFLICT (tenant_id) WHERE status IN ('PENDING', 'RUNNING', 'FAILED')
                DO NOTHING
                RETURNING %s
                """.formatted(COLUMNS);
        String select = """
                SELECT %s
                FROM tenant_migration_job
                WHERE tenant_id = ?
                  AND status IN ('PENDING', 'RUNNING', 'FAILED')
                """.formatted(COLUMNS);

        for (int attempt = 0; attempt < 3; attempt++) {
            Optional<TenantMigrationJob> created = querySingle(insert, statement -> {
                statement.setObject(1, UUID.randomUUID());
                statement.setString(2, tenantId);
                statement.setInt(3, pageSize);
            });
            if (created.isPresent()) {
                return created.get();
            }

            Optional<TenantMigrationJob> unfinished = querySingle(select, statement -> statement.setString(1, tenantId));
            if (unfinished.isPresent()) {
                return unfinished.get();
            }
        }
        throw new IllegalStateException("Cannot create migration job for tenant " + tenantId);
    }

    @Override
    public Optional<TenantMigrationJob> findById(UUID jobId) {
        String sql = "SELECT " + COLUMNS + " FROM tenant_migration_job WHERE job_id = ?";
        return querySingle(sql, statement -> statement.setObject(1, jobId));
    }

    /**
     * Same idea as the candidate claim: one atomic statement decides the owner,
     * time comes from the database clock.
     */
    @Override
    public Optional<TenantMigrationJob> tryClaim(UUID jobId, UUID leaseOwner) {
        String sql = """
                UPDATE tenant_migration_job
                SET status = 'RUNNING',
                    lease_owner = ?,
                    last_error = NULL,
                    updated_at = now()
                WHERE job_id = ?
                  AND (status IN ('PENDING', 'FAILED')
                       OR (status = 'RUNNING'
                           AND updated_at < now() - (? * INTERVAL '1 millisecond')))
                RETURNING %s
                """.formatted(COLUMNS);

        return querySingle(sql, statement -> {
            statement.setObject(1, leaseOwner);
            statement.setObject(2, jobId);
            statement.setLong(3, leaseTimeout.toMillis());
        });
    }

    @Override
    public boolean recordPage(UUID jobId, UUID leaseOwner, int nextPage, JobProgress progress) {
        String sql = """
                UPDATE tenant_migration_job
                SET next_page = ?,
                    processed_count = processed_count + ?,
                    succeeded_count = succeeded_count + ?,
                    skipped_count = skipped_count + ?,
                    failed_count = failed_count + ?,
                    updated_at = now()
                WHERE job_id = ?
                  AND status = 'RUNNING'
                  AND lease_owner = ?
                """;

        return update(sql, statement -> {
            statement.setInt(1, nextPage);
            statement.setLong(2, progress.processed());
            statement.setLong(3, progress.succeeded());
            statement.setLong(4, progress.skipped());
            statement.setLong(5, progress.failed());
            statement.setObject(6, jobId);
            statement.setObject(7, leaseOwner);
        });
    }

    @Override
    public boolean complete(UUID jobId, UUID leaseOwner) {
        String sql = """
                UPDATE tenant_migration_job
                SET status = CASE WHEN failed_count > 0 THEN 'COMPLETED_WITH_ERRORS' ELSE 'COMPLETED' END,
                    lease_owner = NULL,
                    updated_at = now()
                WHERE job_id = ?
                  AND status = 'RUNNING'
                  AND lease_owner = ?
                """;

        return update(sql, statement -> {
            statement.setObject(1, jobId);
            statement.setObject(2, leaseOwner);
        });
    }

    @Override
    public boolean markFailed(UUID jobId, UUID leaseOwner, String error) {
        String sql = """
                UPDATE tenant_migration_job
                SET status = 'FAILED',
                    last_error = ?,
                    lease_owner = NULL,
                    updated_at = now()
                WHERE job_id = ?
                  AND status = 'RUNNING'
                  AND lease_owner = ?
                """;

        return update(sql, statement -> {
            statement.setString(1, truncate(error));
            statement.setObject(2, jobId);
            statement.setObject(3, leaseOwner);
        });
    }

    @Override
    public List<UUID> findResumable(int limit) {
        String sql = """
                SELECT job_id
                FROM tenant_migration_job
                WHERE status = 'PENDING'
                   OR (status = 'RUNNING'
                       AND updated_at < now() - (? * INTERVAL '1 millisecond'))
                ORDER BY updated_at
                LIMIT ?
                """;

        try (
                Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)
        ) {
            statement.setLong(1, leaseTimeout.toMillis());
            statement.setInt(2, limit);

            List<UUID> jobIds = new ArrayList<>();
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    jobIds.add(resultSet.getObject(1, UUID.class));
                }
            }
            return jobIds;
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot find resumable migration jobs", e);
        }
    }

    private Optional<TenantMigrationJob> querySingle(String sql, StatementBinder binder) {
        try (
                Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)
        ) {
            binder.bind(statement);

            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? Optional.of(mapJob(resultSet)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot access migration job", e);
        }
    }

    private boolean update(String sql, StatementBinder binder) {
        try (
                Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)
        ) {
            binder.bind(statement);

            return statement.executeUpdate() == 1;
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot update migration job", e);
        }
    }

    private static TenantMigrationJob mapJob(ResultSet resultSet) throws SQLException {
        return new TenantMigrationJob(
                resultSet.getObject("job_id", UUID.class),
                resultSet.getString("tenant_id"),
                TenantMigrationJobStatus.valueOf(resultSet.getString("status")),
                resultSet.getInt("page_size"),
                resultSet.getInt("next_page"),
                resultSet.getLong("processed_count"),
                resultSet.getLong("succeeded_count"),
                resultSet.getLong("skipped_count"),
                resultSet.getLong("failed_count"),
                resultSet.getString("last_error"),
                resultSet.getObject("created_at", OffsetDateTime.class).toInstant(),
                resultSet.getObject("updated_at", OffsetDateTime.class).toInstant()
        );
    }

    private static String truncate(String error) {
        if (error == null || error.length() <= MAX_ERROR_LENGTH) {
            return error;
        }
        return error.substring(0, MAX_ERROR_LENGTH);
    }

    @FunctionalInterface
    private interface StatementBinder {
        void bind(PreparedStatement statement) throws SQLException;
    }
}
