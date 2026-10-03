package com.dch.smartrecruters.state.jdbc;

import com.dch.smartrecruters.state.MigrationRecordRepository;
import com.dch.smartrecruters.state.MigrationStatus;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Duration;

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
     * Returns true only for the worker whose statement changed a row.
     * Time is taken from the database clock (now()), not from the application nodes.
     */
    @Override
    public boolean tryStart(String tenantId, String sourceRecordId) {
        String sql = """
                INSERT INTO candidate_migration (
                    tenant_id,
                    source_record_id,
                    status,
                    updated_at
                )
                VALUES (?, ?, 'IN_PROGRESS', now())
                ON CONFLICT (tenant_id, source_record_id)
                DO UPDATE SET status = 'IN_PROGRESS',
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
            statement.setLong(3, claimTimeout.toMillis());

            return statement.executeUpdate() == 1;
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot start migration", e);
        }
    }

    @Override
    public void markCompleted(String tenantId, String sourceRecordId) {
        updateStatus(tenantId, sourceRecordId, MigrationStatus.COMPLETED);
    }

    @Override
    public void markFailed(String tenantId, String sourceRecordId) {
        updateStatus(tenantId, sourceRecordId, MigrationStatus.FAILED);
    }

    /**
     * Only IN_PROGRESS can be finished, so a late call (e.g. from a worker whose lease
     * already expired) can never overwrite COMPLETED.
     */
    private void updateStatus(
            String tenantId,
            String sourceRecordId,
            MigrationStatus status
    ) {
        String sql = """
                UPDATE candidate_migration
                SET status = ?,
                    updated_at = now()
                WHERE tenant_id = ?
                  AND source_record_id = ?
                  AND status = 'IN_PROGRESS'
                """;

        try (
                Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)
        ) {
            statement.setString(1, status.name());
            statement.setString(2, tenantId);
            statement.setString(3, sourceRecordId);

            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot update migration status", e);
        }
    }
}
