package com.dch.smartrecruters.state.jdbc;

import com.dch.smartrecruters.state.FingerprintedCandidate;
import com.dch.smartrecruters.state.ReconciliationItem;
import com.dch.smartrecruters.state.ReconciliationItemPage;
import com.dch.smartrecruters.state.ReconciliationRepository;
import com.dch.smartrecruters.state.ReconciliationResult;
import com.dch.smartrecruters.state.ReconciliationRun;
import com.dch.smartrecruters.state.ReconciliationRunStatus;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public class JdbcReconciliationRepository implements ReconciliationRepository {

    private static final int MAX_ERROR_LENGTH = 1000;

    private static final String RUN_COLUMNS = """
            run_id, tenant_id, status, page_size,
            source_count, target_count, matched_count,
            missing_in_target_count, unexpected_in_target_count, mismatched_count,
            last_error, created_at, updated_at, completed_at
            """;

    private final DataSource dataSource;
    private final Duration leaseTimeout;

    public JdbcReconciliationRepository(DataSource dataSource, Duration leaseTimeout) {
        if (leaseTimeout == null || leaseTimeout.isNegative() || leaseTimeout.isZero()) {
            throw new IllegalArgumentException("leaseTimeout must be positive");
        }
        this.dataSource = dataSource;
        this.leaseTimeout = leaseTimeout;
    }

    /**
     * The partial unique index allows one unfinished run per tenant, so concurrent starts end up
     * with the same run. Retried because the conflicting run may finish between INSERT and SELECT.
     */
    @Override
    public ReconciliationRun createOrGetUnfinished(String tenantId, int pageSize) {
        String insert = """
                INSERT INTO reconciliation_run (run_id, tenant_id, status, page_size)
                VALUES (?, ?, 'PENDING', ?)
                ON CONFLICT (tenant_id) WHERE status IN ('PENDING', 'RUNNING')
                DO NOTHING
                RETURNING %s
                """.formatted(RUN_COLUMNS);
        String select = """
                SELECT %s
                FROM reconciliation_run
                WHERE tenant_id = ?
                  AND status IN ('PENDING', 'RUNNING')
                """.formatted(RUN_COLUMNS);

        for (int attempt = 0; attempt < 3; attempt++) {
            Optional<ReconciliationRun> created = queryRun(insert, statement -> {
                statement.setObject(1, UUID.randomUUID());
                statement.setString(2, tenantId);
                statement.setInt(3, pageSize);
            });
            if (created.isPresent()) {
                return created.get();
            }

            Optional<ReconciliationRun> unfinished = queryRun(select, statement -> statement.setString(1, tenantId));
            if (unfinished.isPresent()) {
                return unfinished.get();
            }
        }
        throw new IllegalStateException("Cannot create reconciliation run for tenant " + tenantId);
    }

    @Override
    public Optional<ReconciliationRun> findById(UUID runId) {
        return queryRun(
                "SELECT " + RUN_COLUMNS + " FROM reconciliation_run WHERE run_id = ?",
                statement -> statement.setObject(1, runId)
        );
    }

    @Override
    public Optional<ReconciliationRun> tryClaim(UUID runId, UUID leaseOwner) {
        String sql = """
                UPDATE reconciliation_run
                SET status = 'RUNNING',
                    lease_owner = ?,
                    updated_at = now()
                WHERE run_id = ?
                  AND status = 'PENDING'
                RETURNING %s
                """.formatted(RUN_COLUMNS);

        return queryRun(sql, statement -> {
            statement.setObject(1, leaseOwner);
            statement.setObject(2, runId);
        });
    }

    /**
     * Idempotent per (run_id, external_id): re-reading the same candidate (e.g. shifted offset pages)
     * just overwrites its own source columns and never touches the target columns.
     */
    @Override
    public void recordSource(UUID runId, String tenantId, List<FingerprintedCandidate> candidates) {
        recordSide(runId, tenantId, candidates, """
                INSERT INTO candidate_reconciliation_item (run_id, tenant_id, external_id, source_seen, source_fingerprint)
                VALUES (?, ?, ?, TRUE, ?)
                ON CONFLICT (run_id, external_id)
                DO UPDATE SET source_seen = TRUE,
                              source_fingerprint = EXCLUDED.source_fingerprint
                """);
    }

    @Override
    public void recordTarget(UUID runId, String tenantId, List<FingerprintedCandidate> candidates) {
        recordSide(runId, tenantId, candidates, """
                INSERT INTO candidate_reconciliation_item (run_id, tenant_id, external_id, target_seen, target_fingerprint)
                VALUES (?, ?, ?, TRUE, ?)
                ON CONFLICT (run_id, external_id)
                DO UPDATE SET target_seen = TRUE,
                              target_fingerprint = EXCLUDED.target_fingerprint
                """);
    }

    private void recordSide(UUID runId, String tenantId, List<FingerprintedCandidate> candidates, String sql) {
        if (candidates.isEmpty()) {
            return;
        }

        try (
                Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)
        ) {
            // one page = one batch = one transaction
            connection.setAutoCommit(false);
            try {
                for (FingerprintedCandidate candidate : candidates) {
                    statement.setObject(1, runId);
                    statement.setString(2, tenantId);
                    statement.setString(3, candidate.externalId());
                    statement.setString(4, candidate.fingerprint());
                    statement.addBatch();
                }
                statement.executeBatch();
                connection.commit();
            } catch (SQLException | RuntimeException e) {
                connection.rollback();
                throw e;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot record reconciliation page", e);
        }
    }

    @Override
    public boolean heartbeat(UUID runId, UUID leaseOwner) {
        return update("""
                UPDATE reconciliation_run
                SET updated_at = now()
                WHERE run_id = ?
                  AND status = 'RUNNING'
                  AND lease_owner = ?
                """, statement -> {
            statement.setObject(1, runId);
            statement.setObject(2, leaseOwner);
        });
    }

    /**
     * Classification and summary in one transaction. The run row is locked first and must still be
     * RUNNING for this owner, so a worker that lost the run cannot finalize it.
     */
    @Override
    public Optional<ReconciliationRun> complete(UUID runId, UUID leaseOwner) {
        String lock = """
                SELECT 1
                FROM reconciliation_run
                WHERE run_id = ?
                  AND status = 'RUNNING'
                  AND lease_owner = ?
                FOR UPDATE
                """;
        String classify = """
                UPDATE candidate_reconciliation_item
                SET result = CASE
                        WHEN source_seen AND target_seen AND source_fingerprint = target_fingerprint THEN 'MATCHED'
                        WHEN source_seen AND target_seen THEN 'MISMATCHED'
                        WHEN source_seen THEN 'MISSING_IN_TARGET'
                        ELSE 'UNEXPECTED_IN_TARGET'
                    END
                WHERE run_id = ?
                """;
        String summarize = """
                UPDATE reconciliation_run r
                SET status = 'COMPLETED',
                    source_count = c.source_count,
                    target_count = c.target_count,
                    matched_count = c.matched_count,
                    missing_in_target_count = c.missing_in_target_count,
                    unexpected_in_target_count = c.unexpected_in_target_count,
                    mismatched_count = c.mismatched_count,
                    lease_owner = NULL,
                    updated_at = now(),
                    completed_at = now()
                FROM (
                    SELECT count(*) FILTER (WHERE source_seen)                     AS source_count,
                           count(*) FILTER (WHERE target_seen)                     AS target_count,
                           count(*) FILTER (WHERE result = 'MATCHED')              AS matched_count,
                           count(*) FILTER (WHERE result = 'MISSING_IN_TARGET')    AS missing_in_target_count,
                           count(*) FILTER (WHERE result = 'UNEXPECTED_IN_TARGET') AS unexpected_in_target_count,
                           count(*) FILTER (WHERE result = 'MISMATCHED')           AS mismatched_count
                    FROM candidate_reconciliation_item
                    WHERE run_id = ?
                ) c
                WHERE r.run_id = ?
                """;

        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                try (PreparedStatement statement = connection.prepareStatement(lock)) {
                    statement.setObject(1, runId);
                    statement.setObject(2, leaseOwner);
                    try (ResultSet resultSet = statement.executeQuery()) {
                        if (!resultSet.next()) {
                            connection.rollback();
                            return Optional.empty();
                        }
                    }
                }
                try (PreparedStatement statement = connection.prepareStatement(classify)) {
                    statement.setObject(1, runId);
                    statement.executeUpdate();
                }
                try (PreparedStatement statement = connection.prepareStatement(summarize)) {
                    statement.setObject(1, runId);
                    statement.setObject(2, runId);
                    statement.executeUpdate();
                }
                connection.commit();
            } catch (SQLException | RuntimeException e) {
                connection.rollback();
                throw e;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot complete reconciliation run", e);
        }

        return findById(runId);
    }

    @Override
    public boolean markFailed(UUID runId, UUID leaseOwner, String error) {
        return update("""
                UPDATE reconciliation_run
                SET status = 'FAILED',
                    last_error = ?,
                    lease_owner = NULL,
                    updated_at = now(),
                    completed_at = now()
                WHERE run_id = ?
                  AND status = 'RUNNING'
                  AND lease_owner = ?
                """, statement -> {
            statement.setString(1, truncate(error));
            statement.setObject(2, runId);
            statement.setObject(3, leaseOwner);
        });
    }

    @Override
    public int failAbandoned() {
        String sql = """
                UPDATE reconciliation_run
                SET status = 'FAILED',
                    last_error = 'Abandoned: no progress within lease timeout',
                    lease_owner = NULL,
                    updated_at = now(),
                    completed_at = now()
                WHERE status = 'RUNNING'
                  AND updated_at < now() - (? * INTERVAL '1 millisecond')
                """;

        try (
                Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)
        ) {
            statement.setLong(1, leaseTimeout.toMillis());
            return statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot fail abandoned reconciliation runs", e);
        }
    }

    @Override
    public List<UUID> findPending(int limit) {
        String sql = """
                SELECT run_id
                FROM reconciliation_run
                WHERE status = 'PENDING'
                ORDER BY created_at
                LIMIT ?
                """;

        try (
                Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)
        ) {
            statement.setInt(1, limit);
            List<UUID> runIds = new ArrayList<>();
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    runIds.add(resultSet.getObject(1, UUID.class));
                }
            }
            return runIds;
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot find pending reconciliation runs", e);
        }
    }

    /**
     * Ordered by external_id, served by the (run_id, result, external_id) index; LIMIT/OFFSET paging with
     * a bounded page size. One row more than the page size is read to know whether another page exists.
     */
    @Override
    public ReconciliationItemPage findItems(UUID runId, ReconciliationResult result, int page, int size) {
        String sql = result == null
                ? """
                SELECT external_id, result, source_fingerprint, target_fingerprint
                FROM candidate_reconciliation_item
                WHERE run_id = ?
                  AND result IN ('MISSING_IN_TARGET', 'UNEXPECTED_IN_TARGET', 'MISMATCHED')
                ORDER BY external_id
                LIMIT ? OFFSET ?
                """
                : """
                SELECT external_id, result, source_fingerprint, target_fingerprint
                FROM candidate_reconciliation_item
                WHERE run_id = ?
                  AND result = ?
                ORDER BY external_id
                LIMIT ? OFFSET ?
                """;

        try (
                Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)
        ) {
            int index = 1;
            statement.setObject(index++, runId);
            if (result != null) {
                statement.setString(index++, result.name());
            }
            statement.setInt(index++, size + 1);
            statement.setLong(index, (long) page * size);

            List<ReconciliationItem> items = new ArrayList<>();
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    items.add(new ReconciliationItem(
                            resultSet.getString("external_id"),
                            ReconciliationResult.valueOf(resultSet.getString("result")),
                            resultSet.getString("source_fingerprint"),
                            resultSet.getString("target_fingerprint")
                    ));
                }
            }

            boolean hasNext = items.size() > size;
            return new ReconciliationItemPage(hasNext ? items.subList(0, size) : items, page, size, hasNext);
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot read reconciliation items", e);
        }
    }

    private Optional<ReconciliationRun> queryRun(String sql, StatementBinder binder) {
        try (
                Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)
        ) {
            binder.bind(statement);

            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? Optional.of(mapRun(resultSet)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot access reconciliation run", e);
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
            throw new IllegalStateException("Cannot update reconciliation run", e);
        }
    }

    private static ReconciliationRun mapRun(ResultSet resultSet) throws SQLException {
        return new ReconciliationRun(
                resultSet.getObject("run_id", UUID.class),
                resultSet.getString("tenant_id"),
                ReconciliationRunStatus.valueOf(resultSet.getString("status")),
                resultSet.getInt("page_size"),
                resultSet.getLong("source_count"),
                resultSet.getLong("target_count"),
                resultSet.getLong("matched_count"),
                resultSet.getLong("missing_in_target_count"),
                resultSet.getLong("unexpected_in_target_count"),
                resultSet.getLong("mismatched_count"),
                resultSet.getString("last_error"),
                instant(resultSet, "created_at"),
                instant(resultSet, "updated_at"),
                instant(resultSet, "completed_at")
        );
    }

    private static Instant instant(ResultSet resultSet, String column) throws SQLException {
        OffsetDateTime value = resultSet.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
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
