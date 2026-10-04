package com.dch.smartrecruters.state.jdbc;

import com.dch.smartrecruters.state.MigrationStatus;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real PostgreSQL (Testcontainers) with the production schema applied by Flyway.
 * Skipped when no Docker-compatible runtime is available.
 */
@Testcontainers(disabledWithoutDocker = true)
class JdbcMigrationRecordRepositoryIntegrationTest {

    private static final int WORKERS = 50;
    private static final Duration CLAIM_TIMEOUT = Duration.ofMinutes(5);

    private static final String TENANT = "tenant-1";
    private static final String CANDIDATE = "candidate-1";

    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17");

    private static HikariDataSource dataSource;
    private static JdbcMigrationRecordRepository repository;

    @BeforeAll
    static void setUpDatabase() throws InterruptedException {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(POSTGRES.getJdbcUrl());
        config.setUsername(POSTGRES.getUsername());
        config.setPassword(POSTGRES.getPassword());
        config.setMaximumPoolSize(WORKERS);
        config.setMinimumIdle(WORKERS);
        dataSource = new HikariDataSource(config);

        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .load()
                .migrate();

        // all workers should hold a physical connection before the race starts
        while (dataSource.getHikariPoolMXBean().getTotalConnections() < WORKERS) {
            Thread.sleep(50);
        }

        repository = new JdbcMigrationRecordRepository(dataSource, CLAIM_TIMEOUT);
    }

    @AfterAll
    static void closeDataSource() {
        dataSource.close();
    }

    @BeforeEach
    void cleanTable() throws SQLException {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("TRUNCATE candidate_migration");
        }
    }

    @Test
    void shouldLetExactlyOneWorkerClaimNewRecord() throws Exception {
        int claimed = claimConcurrently(TENANT, CANDIDATE);

        assertEquals(1, claimed);
        assertEquals("IN_PROGRESS", status(TENANT, CANDIDATE));
        assertEquals(1, rowCount());
    }

    @Test
    void shouldLetExactlyOneWorkerReclaimFailedRecord() throws Exception {
        insert(TENANT, CANDIDATE, "FAILED", Duration.ofSeconds(10));
        Instant before = updatedAt(TENANT, CANDIDATE);

        int claimed = claimConcurrently(TENANT, CANDIDATE);

        assertEquals(1, claimed);
        assertEquals("IN_PROGRESS", status(TENANT, CANDIDATE));
        assertTrue(updatedAt(TENANT, CANDIDATE).isAfter(before));
    }

    @Test
    void shouldNotClaimFreshInProgressRecord() throws Exception {
        insert(TENANT, CANDIDATE, "IN_PROGRESS", Duration.ofMinutes(1));
        Instant before = updatedAt(TENANT, CANDIDATE);

        assertFalse(repository.tryStart(TENANT, CANDIDATE));
        assertEquals(0, claimConcurrently(TENANT, CANDIDATE));

        assertEquals("IN_PROGRESS", status(TENANT, CANDIDATE));
        assertEquals(before, updatedAt(TENANT, CANDIDATE));
    }

    @Test
    void shouldLetExactlyOneWorkerReclaimStaleInProgressRecord() throws Exception {
        insert(TENANT, CANDIDATE, "IN_PROGRESS", CLAIM_TIMEOUT.plusMinutes(5));
        Instant before = updatedAt(TENANT, CANDIDATE);

        int claimed = claimConcurrently(TENANT, CANDIDATE);

        assertEquals(1, claimed);
        assertEquals("IN_PROGRESS", status(TENANT, CANDIDATE));
        assertTrue(updatedAt(TENANT, CANDIDATE).isAfter(before));

        // the new owner holds a fresh lease
        assertFalse(repository.tryStart(TENANT, CANDIDATE));
    }

    @Test
    void shouldNeverClaimCompletedRecordEvenWhenOld() throws Exception {
        insert(TENANT, CANDIDATE, "COMPLETED", Duration.ofDays(1));
        Instant before = updatedAt(TENANT, CANDIDATE);

        assertFalse(repository.tryStart(TENANT, CANDIDATE));
        assertEquals(0, claimConcurrently(TENANT, CANDIDATE));

        assertEquals("COMPLETED", status(TENANT, CANDIDATE));
        assertEquals(before, updatedAt(TENANT, CANDIDATE));
    }

    @Test
    void shouldTreatSameCandidateInOtherTenantAsSeparateRecord() {
        assertTrue(repository.tryStart("tenant-1", CANDIDATE));
        assertTrue(repository.tryStart("tenant-2", CANDIDATE));
        assertFalse(repository.tryStart("tenant-1", CANDIDATE));
    }

    @Test
    void shouldRefreshUpdatedAtWhenMarkingCompletedAndFailed() throws Exception {
        insert(TENANT, CANDIDATE, "IN_PROGRESS", Duration.ofMinutes(1));
        insert("tenant-2", CANDIDATE, "IN_PROGRESS", Duration.ofMinutes(1));
        Instant completedBefore = updatedAt(TENANT, CANDIDATE);
        Instant failedBefore = updatedAt("tenant-2", CANDIDATE);

        repository.markCompleted(TENANT, CANDIDATE);
        repository.markFailed("tenant-2", CANDIDATE);

        assertEquals("COMPLETED", status(TENANT, CANDIDATE));
        assertTrue(updatedAt(TENANT, CANDIDATE).isAfter(completedBefore));
        assertEquals("FAILED", status("tenant-2", CANDIDATE));
        assertTrue(updatedAt("tenant-2", CANDIDATE).isAfter(failedBefore));
    }

    @Test
    void shouldNotOverwriteCompletedWithLateFailureFromExpiredWorker() throws Exception {
        insert(TENANT, CANDIDATE, "COMPLETED", Duration.ofMinutes(1));

        repository.markFailed(TENANT, CANDIDATE);

        assertEquals("COMPLETED", status(TENANT, CANDIDATE));
    }

    @Test
    void shouldReadCurrentStatus() throws Exception {
        insert(TENANT, CANDIDATE, "COMPLETED", Duration.ofMinutes(1));
        insert(TENANT, "candidate-2", "IN_PROGRESS", Duration.ofMinutes(1));

        assertEquals(Optional.of(MigrationStatus.COMPLETED), repository.findStatus(TENANT, CANDIDATE));
        assertEquals(Optional.of(MigrationStatus.IN_PROGRESS), repository.findStatus(TENANT, "candidate-2"));
        assertEquals(Optional.empty(), repository.findStatus("tenant-2", CANDIDATE));
    }

    private int claimConcurrently(String tenantId, String sourceRecordId) throws Exception {
        CountDownLatch ready = new CountDownLatch(WORKERS);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> results = new ArrayList<>();

        try (ExecutorService executor = Executors.newFixedThreadPool(WORKERS)) {
            for (int i = 0; i < WORKERS; i++) {
                results.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    return repository.tryStart(tenantId, sourceRecordId);
                }));
            }

            ready.await();
            start.countDown();

            int claimed = 0;
            for (Future<Boolean> result : results) {
                if (result.get()) {
                    claimed++;
                }
            }
            return claimed;
        }
    }

    private void insert(String tenantId, String sourceRecordId, String status, Duration age) throws SQLException {
        String sql = """
                INSERT INTO candidate_migration (tenant_id, source_record_id, status, updated_at)
                VALUES (?, ?, ?, now() - (? * INTERVAL '1 millisecond'))
                """;
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, tenantId);
            statement.setString(2, sourceRecordId);
            statement.setString(3, status);
            statement.setLong(4, age.toMillis());
            statement.executeUpdate();
        }
    }

    private String status(String tenantId, String sourceRecordId) throws SQLException {
        return queryColumn(tenantId, sourceRecordId, "status", String.class);
    }

    private Instant updatedAt(String tenantId, String sourceRecordId) throws SQLException {
        return queryColumn(tenantId, sourceRecordId, "updated_at", OffsetDateTime.class).toInstant();
    }

    private <T> T queryColumn(String tenantId, String sourceRecordId, String column, Class<T> type)
            throws SQLException {
        String sql = "SELECT " + column + " FROM candidate_migration WHERE tenant_id = ? AND source_record_id = ?";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, tenantId);
            statement.setString(2, sourceRecordId);
            try (ResultSet resultSet = statement.executeQuery()) {
                assertTrue(resultSet.next(), "row not found");
                return resultSet.getObject(1, type);
            }
        }
    }

    private int rowCount() throws SQLException {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("SELECT count(*) FROM candidate_migration")) {
            resultSet.next();
            return resultSet.getInt(1);
        }
    }
}
