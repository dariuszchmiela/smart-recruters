package com.dch.smartrecruters.state.jdbc;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Lightweight check of the claim statement and its result mapping.
 * Real concurrency semantics of ON CONFLICT need PostgreSQL and are not tested here.
 */
class JdbcMigrationRecordRepositoryTest {

    private PreparedStatement statement;
    private Connection connection;
    private JdbcMigrationRecordRepository repository;

    @BeforeEach
    void setUp() throws SQLException {
        DataSource dataSource = mock(DataSource.class);
        connection = mock(Connection.class);
        statement = mock(PreparedStatement.class);

        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenReturn(statement);

        repository = new JdbcMigrationRecordRepository(dataSource);
    }

    @Test
    void shouldClaimWhenStatementInsertedOrReclaimedRow() throws SQLException {
        when(statement.executeUpdate()).thenReturn(1);

        assertTrue(repository.tryStart("tenant-1", "candidate-1"));

        verify(statement).setString(1, "tenant-1");
        verify(statement).setString(2, "candidate-1");
    }

    @Test
    void shouldNotClaimWhenNoRowChanged() throws SQLException {
        when(statement.executeUpdate()).thenReturn(0);

        assertFalse(repository.tryStart("tenant-1", "candidate-1"));
    }

    @Test
    void shouldReclaimOnlyFailedRecordsInSingleStatement() throws SQLException {
        when(statement.executeUpdate()).thenReturn(1);

        repository.tryStart("tenant-1", "candidate-1");

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(connection).prepareStatement(sql.capture());
        String normalized = sql.getValue().replaceAll("\\s+", " ");

        assertTrue(normalized.contains("ON CONFLICT (tenant_id, source_record_id) DO UPDATE SET status = 'IN_PROGRESS'"));
        assertTrue(normalized.contains("WHERE candidate_migration.status = 'FAILED'"));
    }
}
