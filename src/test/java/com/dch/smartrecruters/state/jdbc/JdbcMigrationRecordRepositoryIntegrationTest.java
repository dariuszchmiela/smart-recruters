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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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

        assertFalse(repository.tryStart(TENANT, CANDIDATE, UUID.randomUUID()));
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
        assertFalse(repository.tryStart(TENANT, CANDIDATE, UUID.randomUUID()));
    }

    @Test
    void shouldNeverClaimCompletedRecordEvenWhenOld() throws Exception {
        insert(TENANT, CANDIDATE, "COMPLETED", Duration.ofDays(1));
        Instant before = updatedAt(TENANT, CANDIDATE);

        assertFalse(repository.tryStart(TENANT, CANDIDATE, UUID.randomUUID()));
        assertEquals(0, claimConcurrently(TENANT, CANDIDATE));

        assertEquals("COMPLETED", status(TENANT, CANDIDATE));
        assertEquals(before, updatedAt(TENANT, CANDIDATE));
    }

    @Test
    void shouldTreatSameCandidateInOtherTenantAsSeparateRecord() {
        assertTrue(repository.tryStart("tenant-1", CANDIDATE, UUID.randomUUID()));
        assertTrue(repository.tryStart("tenant-2", CANDIDATE, UUID.randomUUID()));
        assertFalse(repository.tryStart("tenant-1", CANDIDATE, UUID.randomUUID()));
    }

    @Test
    void shouldRefreshUpdatedAtWhenMarkingCompletedAndFailed() throws Exception {
        UUID owner = UUID.randomUUID();
        insert(TENANT, CANDIDATE, "IN_PROGRESS", Duration.ofMinutes(1), owner);
        insert("tenant-2", CANDIDATE, "IN_PROGRESS", Duration.ofMinutes(1), owner);
        Instant completedBefore = updatedAt(TENANT, CANDIDATE);
        Instant failedBefore = updatedAt("tenant-2", CANDIDATE);

        assertTrue(repository.markCompleted(TENANT, CANDIDATE, owner));
        assertTrue(repository.markFailed("tenant-2", CANDIDATE, owner));

        assertEquals("COMPLETED", status(TENANT, CANDIDATE));
        assertTrue(updatedAt(TENANT, CANDIDATE).isAfter(completedBefore));
        assertEquals("FAILED", status("tenant-2", CANDIDATE));
        assertTrue(updatedAt("tenant-2", CANDIDATE).isAfter(failedBefore));
    }

    @Test
    void shouldNotOverwriteCompletedWithLateFailureFromExpiredWorker() throws Exception {
        UUID owner = UUID.randomUUID();
        insert(TENANT, CANDIDATE, "COMPLETED", Duration.ofMinutes(1), owner);

        assertFalse(repository.markFailed(TENANT, CANDIDATE, owner));

        assertEquals("COMPLETED", status(TENANT, CANDIDATE));
    }

    @Test
    void shouldSetOwnerOnEveryKindOfClaim() throws Exception {
        UUID newRecordOwner = UUID.randomUUID();
        assertTrue(repository.tryStart(TENANT, CANDIDATE, newRecordOwner));
        assertEquals(newRecordOwner, leaseOwner(TENANT, CANDIDATE));

        insert(TENANT, "failed", "FAILED", Duration.ofSeconds(10));
        UUID failedOwner = UUID.randomUUID();
        assertTrue(repository.tryStart(TENANT, "failed", failedOwner));
        assertEquals(failedOwner, leaseOwner(TENANT, "failed"));
    }

    @Test
    void shouldLetWorkerBReclaimStaleCandidate() throws Exception {
        UUID workerA = UUID.randomUUID();

        UUID workerB = takenOverFrom(workerA);

        assertEquals("IN_PROGRESS", status(TENANT, CANDIDATE));
        assertEquals(workerB, leaseOwner(TENANT, CANDIDATE));
    }

    @Test
    void shouldNotLetStaleWorkerMarkCompleted() throws Exception {
        UUID workerA = UUID.randomUUID();
        UUID workerB = takenOverFrom(workerA);
        Instant before = updatedAt(TENANT, CANDIDATE);

        assertFalse(repository.markCompleted(TENANT, CANDIDATE, workerA));

        assertEquals("IN_PROGRESS", status(TENANT, CANDIDATE));
        assertEquals(workerB, leaseOwner(TENANT, CANDIDATE));
        assertEquals(before, updatedAt(TENANT, CANDIDATE));
    }

    @Test
    void shouldNotLetStaleWorkerMarkFailed() throws Exception {
        UUID workerA = UUID.randomUUID();
        UUID workerB = takenOverFrom(workerA);

        assertFalse(repository.markFailed(TENANT, CANDIDATE, workerA));

        assertEquals("IN_PROGRESS", status(TENANT, CANDIDATE));
        assertEquals(workerB, leaseOwner(TENANT, CANDIDATE));
        // B's claim is still fresh: nobody else can take the record now
        assertFalse(repository.tryStart(TENANT, CANDIDATE, UUID.randomUUID()));
    }

    @Test
    void shouldLetCurrentOwnerCompleteTakenOverCandidate() throws Exception {
        UUID workerA = UUID.randomUUID();
        UUID workerB = takenOverFrom(workerA);
        assertFalse(repository.markFailed(TENANT, CANDIDATE, workerA));

        assertTrue(repository.markCompleted(TENANT, CANDIDATE, workerB));

        assertEquals("COMPLETED", status(TENANT, CANDIDATE));
        assertNull(leaseOwner(TENANT, CANDIDATE));
    }

    @Test
    void shouldKeepCompletedTerminalForEveryWorker() throws Exception {
        UUID workerA = UUID.randomUUID();
        UUID workerB = takenOverFrom(workerA);
        assertTrue(repository.markCompleted(TENANT, CANDIDATE, workerB));
        age(TENANT, CANDIDATE, Duration.ofDays(1));
        Instant before = updatedAt(TENANT, CANDIDATE);

        assertFalse(repository.markFailed(TENANT, CANDIDATE, workerA));
        assertFalse(repository.markCompleted(TENANT, CANDIDATE, workerA));
        assertFalse(repository.markFailed(TENANT, CANDIDATE, workerB));
        assertFalse(repository.tryStart(TENANT, CANDIDATE, UUID.randomUUID()));
        assertEquals(0, claimConcurrently(TENANT, CANDIDATE));

        assertEquals("COMPLETED", status(TENANT, CANDIDATE));
        assertEquals(before, updatedAt(TENANT, CANDIDATE));
    }

    @Test
    void shouldLeaveExactlyOneCurrentOwnerAfterConcurrentReclaim() throws Exception {
        UUID workerA = UUID.randomUUID();
        assertTrue(repository.tryStart(TENANT, CANDIDATE, workerA));
        age(TENANT, CANDIDATE, CLAIM_TIMEOUT.plusMinutes(1));

        List<UUID> winners = claimantsWinning(TENANT, CANDIDATE);

        assertEquals(1, winners.size());
        UUID currentOwner = winners.getFirst();
        assertEquals(currentOwner, leaseOwner(TENANT, CANDIDATE));
        assertFalse(repository.markCompleted(TENANT, CANDIDATE, workerA));
        assertTrue(repository.markCompleted(TENANT, CANDIDATE, currentOwner));
        assertEquals("COMPLETED", status(TENANT, CANDIDATE));
    }

    @Test
    void shouldNotLetAnyoneFinishLegacyInProgressRowWithoutOwnerUntilReclaimed() throws Exception {
        // IN_PROGRESS rows that existed before V5 have no owner
        insert(TENANT, CANDIDATE, "IN_PROGRESS", Duration.ofMinutes(1));

        assertFalse(repository.markCompleted(TENANT, CANDIDATE, UUID.randomUUID()));

        age(TENANT, CANDIDATE, CLAIM_TIMEOUT.plusMinutes(1));
        UUID owner = UUID.randomUUID();
        assertTrue(repository.tryStart(TENANT, CANDIDATE, owner));
        assertTrue(repository.markCompleted(TENANT, CANDIDATE, owner));
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
        return claimantsWinning(tenantId, sourceRecordId).size();
    }

    /**
     * Every worker races with its own owner token; returns the tokens whose claim succeeded.
     */
    private List<UUID> claimantsWinning(String tenantId, String sourceRecordId) throws Exception {
        CountDownLatch ready = new CountDownLatch(WORKERS);
        CountDownLatch start = new CountDownLatch(1);
        Map<UUID, Future<Boolean>> results = new LinkedHashMap<>();

        try (ExecutorService executor = Executors.newFixedThreadPool(WORKERS)) {
            for (int i = 0; i < WORKERS; i++) {
                UUID owner = UUID.randomUUID();
                results.put(owner, executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    return repository.tryStart(tenantId, sourceRecordId, owner);
                }));
            }

            ready.await();
            start.countDown();

            List<UUID> winners = new ArrayList<>();
            for (Map.Entry<UUID, Future<Boolean>> result : results.entrySet()) {
                if (result.getValue().get()) {
                    winners.add(result.getKey());
                }
            }
            return winners;
        }
    }

    private void insert(String tenantId, String sourceRecordId, String status, Duration age) throws SQLException {
        insert(tenantId, sourceRecordId, status, age, null);
    }

    private void insert(String tenantId, String sourceRecordId, String status, Duration age, UUID leaseOwner)
            throws SQLException {
        String sql = """
                INSERT INTO candidate_migration (tenant_id, source_record_id, status, lease_owner, updated_at)
                VALUES (?, ?, ?, ?, now() - (? * INTERVAL '1 millisecond'))
                """;
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, tenantId);
            statement.setString(2, sourceRecordId);
            statement.setString(3, status);
            statement.setObject(4, leaseOwner);
            statement.setLong(5, age.toMillis());
            statement.executeUpdate();
        }
    }

    private UUID leaseOwner(String tenantId, String sourceRecordId) throws SQLException {
        return queryColumn(tenantId, sourceRecordId, "lease_owner", UUID.class);
    }

    private void age(String tenantId, String sourceRecordId, Duration age) throws SQLException {
        String sql = """
                UPDATE candidate_migration SET updated_at = now() - (? * INTERVAL '1 millisecond')
                WHERE tenant_id = ? AND source_record_id = ?
                """;
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, age.toMillis());
            statement.setString(2, tenantId);
            statement.setString(3, sourceRecordId);
            statement.executeUpdate();
        }
    }

    /**
     * Worker A claims, its claim goes stale, worker B takes it over. Returns B's token.
     */
    private UUID takenOverFrom(UUID workerA) throws SQLException {
        assertTrue(repository.tryStart(TENANT, CANDIDATE, workerA));
        age(TENANT, CANDIDATE, CLAIM_TIMEOUT.plusMinutes(1));
        UUID workerB = UUID.randomUUID();
        assertTrue(repository.tryStart(TENANT, CANDIDATE, workerB));
        return workerB;
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
