package com.dch.smartrecruters.state.jdbc;

import com.dch.smartrecruters.state.MigrationRecordRepository;
import com.dch.smartrecruters.state.MigrationStatus;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

public class JdbcMigrationRecordRepository implements MigrationRecordRepository {

    private final DataSource dataSource;

    public JdbcMigrationRecordRepository(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public boolean tryStart(String tenantId, String sourceRecordId) {
        String sql = """
                INSERT INTO candidate_migration (
                    tenant_id,
                    source_record_id,
                    status
                )
                VALUES (?, ?, 'IN_PROGRESS')
                ON CONFLICT (tenant_id, source_record_id)
                DO NOTHING
                """;

        try (
                Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)
        ) {
            statement.setString(1, tenantId);
            statement.setString(2, sourceRecordId);

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

    private void updateStatus(
            String tenantId,
            String sourceRecordId,
            MigrationStatus status
    ) {
        String sql = """
                UPDATE candidate_migration
                SET status = ?
                WHERE tenant_id = ?
                  AND source_record_id = ?
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