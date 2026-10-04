package com.dch.smartrecruters.state.jdbc;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Duration;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Fast check of parameter binding and result mapping (runs without Docker).
 * SQL semantics and concurrency are covered by {@link JdbcMigrationRecordRepositoryIntegrationTest}.
 */
class JdbcMigrationRecordRepositoryTest {

    private PreparedStatement statement;
    private JdbcMigrationRecordRepository repository;

    @BeforeEach
    void setUp() throws SQLException {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        statement = mock(PreparedStatement.class);

        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenReturn(statement);

        repository = new JdbcMigrationRecordRepository(dataSource, Duration.ofMinutes(5));
    }

    @Test
    void shouldClaimWhenStatementChangedRowAndBindClaimTimeout() throws SQLException {
        when(statement.executeUpdate()).thenReturn(1);

        UUID owner = UUID.randomUUID();
        assertTrue(repository.tryStart("tenant-1", "candidate-1", owner));

        verify(statement).setString(1, "tenant-1");
        verify(statement).setString(2, "candidate-1");
        verify(statement).setObject(3, owner);
        verify(statement).setLong(4, Duration.ofMinutes(5).toMillis());
    }

    @Test
    void shouldNotClaimWhenNoRowChanged() throws SQLException {
        when(statement.executeUpdate()).thenReturn(0);

        assertFalse(repository.tryStart("tenant-1", "candidate-1", UUID.randomUUID()));
    }

    @Test
    void shouldRejectNonPositiveClaimTimeout() {
        DataSource dataSource = mock(DataSource.class);

        assertThrows(IllegalArgumentException.class,
                () -> new JdbcMigrationRecordRepository(dataSource, Duration.ZERO));
        assertThrows(IllegalArgumentException.class,
                () -> new JdbcMigrationRecordRepository(dataSource, null));
    }
}
