package com.dch.smartrecruters.state.jdbc;

import com.dch.smartrecruters.state.MigrationRecordRepository;
import com.dch.smartrecruters.state.MigrationStatus;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

public class JdbcMigrationRecordRepository implements MigrationRecordRepository {

    private final DataSource dataSource;
    private final Duration claimTimeout;

    public JdbcMigrationRecordRepository(DataSource dataSource, Duration claimTimeout) {
        if (claimTimeout == null || claimTimeout.isNegative() || claimTimeout.isZero()) {
            throw new IllegalArgumentException("claimTimeout must be positive");
        }
        this.dataSource = dataSource;
        this.claimTimeout = claimTimeout;
    }

    /**
     * Claims the record in a single atomic statement:
     * no row -> insert IN_PROGRESS, FAILED -> IN_PROGRESS,
     * IN_PROGRESS older than claimTimeout (abandoned) -> IN_PROGRESS with a fresh updated_at,
     * fresh IN_PROGRESS / COMPLETED -> no change.
     * Every successful claim installs {@code leaseOwner} as the fencing token of the record.
     * Returns true only for the worker whose statement changed a row.
     * Time is taken from the database clock (now()), not from the application nodes.
     */
    @Override
    public boolean tryStart(String tenantId, String sourceRecordId, UUID leaseOwner) {
        String sql = """
                INSERT INTO candidate_migration (
                    tenant_id,
                    source_record_id,
                    status,
                    lease_owner,
                    updated_at
                )
                VALUES (?, ?, 'IN_PROGRESS', ?, now())
                ON CONFLICT (tenant_id, source_record_id)
                DO UPDATE SET status = 'IN_PROGRESS',
                              lease_owner = EXCLUDED.lease_owner,
                              updated_at = now()
                WHERE candidate_migration.status = 'FAILED'
                   OR (candidate_migration.status = 'IN_PROGRESS'
                       AND candidate_migration.updated_at < now() - (? * INTERVAL '1 millisecond'))
                """;

        try (
                Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)
        ) {
            statement.setString(1, tenantId);
            statement.setString(2, sourceRecordId);
            statement.setObject(3, leaseOwner);
            statement.setLong(4, claimTimeout.toMillis());

            return statement.executeUpdate() == 1;
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot start migration", e);
        }
    }

    @Override
    public boolean markCompleted(String tenantId, String sourceRecordId, UUID leaseOwner) {
        return finish(tenantId, sourceRecordId, leaseOwner, MigrationStatus.COMPLETED);
    }

    @Override
    public boolean markFailed(String tenantId, String sourceRecordId, UUID leaseOwner) {
        return finish(tenantId, sourceRecordId, leaseOwner, MigrationStatus.FAILED);
    }

    @Override
    public Optional<MigrationStatus> findStatus(String tenantId, String sourceRecordId) {
        String sql = """
                SELECT status
                FROM candidate_migration
                WHERE tenant_id = ?
                  AND source_record_id = ?
                """;

        try (
                Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)
        ) {
            statement.setString(1, tenantId);
            statement.setString(2, sourceRecordId);

            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next()
                        ? Optional.of(MigrationStatus.valueOf(resultSet.getString(1)))
                        : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot read migration status", e);
        }
    }

    /**
     * Fenced: only the current owner of an IN_PROGRESS record can finish it. status = 'IN_PROGRESS'
     * alone is not enough - after a stale claim was taken over the row is IN_PROGRESS again, but for
     * another owner. The owner is cleared, so a finished record can no longer be changed by anybody.
     */
    private boolean finish(
            String tenantId,
            String sourceRecordId,
            UUID leaseOwner,
            MigrationStatus status
    ) {
        String sql = """
                UPDATE candidate_migration
                SET status = ?,
                    lease_owner = NULL,
                    updated_at = now()
                WHERE tenant_id = ?
                  AND source_record_id = ?
                  AND status = 'IN_PROGRESS'
                  AND lease_owner = ?
                """;

        try (
                Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)
        ) {
            statement.setString(1, status.name());
            statement.setString(2, tenantId);
            statement.setString(3, sourceRecordId);
            statement.setObject(4, leaseOwner);

            return statement.executeUpdate() == 1;
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot update migration status", e);
        }
    }
}
