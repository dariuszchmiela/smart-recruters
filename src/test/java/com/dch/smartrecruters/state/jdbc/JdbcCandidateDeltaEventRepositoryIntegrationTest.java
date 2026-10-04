package com.dch.smartrecruters.state.jdbc;

import com.dch.smartrecruters.state.DeltaEventRecord;
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
import java.util.ArrayList;
import java.util.List;
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
class JdbcCandidateDeltaEventRepositoryIntegrationTest {

    private static final int WORKERS = 20;
    private static final Duration CLAIM_TIMEOUT = Duration.ofMinutes(5);
    private static final String TENANT = "tenant-1";
    private static final String CANDIDATE = "candidate-1";
    private static final Instant OCCURRED_AT = Instant.parse("2026-01-01T10:00:00Z");

    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17");

    private static HikariDataSource dataSource;
    private static JdbcCandidateDeltaEventRepository repository;

    @BeforeAll
    static void setUpDatabase() {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(POSTGRES.getJdbcUrl());
        config.setUsername(POSTGRES.getUsername());
        config.setPassword(POSTGRES.getPassword());
        config.setMaximumPoolSize(WORKERS);
        dataSource = new HikariDataSource(config);

        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .load()
                .migrate();

        repository = new JdbcCandidateDeltaEventRepository(dataSource, CLAIM_TIMEOUT);
    }

    @AfterAll
    static void closeDataSource() {
        dataSource.close();
    }

    @BeforeEach
    void cleanTable() throws SQLException {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("TRUNCATE candidate_delta_event");
        }
    }

    @Test
    void shouldLetExactlyOneWorkerClaimNewEvent() throws Exception {
        UUID eventId = UUID.randomUUID();

        assertEquals(1, claimConcurrently(eventId));

        DeltaEventRecord record = repository.findById(eventId).orElseThrow();
        assertEquals(MigrationStatus.IN_PROGRESS, record.status());
        assertEquals(TENANT, record.tenantId());
        assertEquals(CANDIDATE, record.candidateId());
        assertEquals(1, record.attempts());
    }

    @Test
    void shouldNeverClaimCompletedEventAgain() throws Exception {
        UUID eventId = UUID.randomUUID();
        UUID owner = UUID.randomUUID();
        assertTrue(claim(eventId, owner));
        assertTrue(repository.markCompleted(eventId, owner));
        age(eventId, Duration.ofDays(1));

        assertFalse(claim(eventId));
        assertEquals(0, claimConcurrently(eventId));
        assertEquals(MigrationStatus.COMPLETED, repository.findById(eventId).orElseThrow().status());
    }

    @Test
    void shouldClaimDifferentEventsOfSameCandidateIndependently() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();

        UUID owner = UUID.randomUUID();
        assertTrue(claim(first, owner));
        assertTrue(repository.markCompleted(first, owner));

        assertTrue(claim(second));
    }

    @Test
    void shouldNotClaimFreshInProgressEvent() {
        UUID eventId = UUID.randomUUID();
        assertTrue(claim(eventId));

        assertFalse(claim(eventId));
        assertEquals(MigrationStatus.IN_PROGRESS, repository.findById(eventId).orElseThrow().status());
    }

    @Test
    void shouldLetExactlyOneWorkerReclaimFailedEvent() throws Exception {
        UUID eventId = UUID.randomUUID();
        UUID owner = UUID.randomUUID();
        assertTrue(claim(eventId, owner));
        assertTrue(repository.markFailed(eventId, owner, "SmartRecruiters unavailable"));
        assertEquals("SmartRecruiters unavailable", repository.findById(eventId).orElseThrow().lastError());

        assertEquals(1, claimConcurrently(eventId));

        DeltaEventRecord record = repository.findById(eventId).orElseThrow();
        assertEquals(MigrationStatus.IN_PROGRESS, record.status());
        assertEquals(2, record.attempts());
    }

    @Test
    void shouldReclaimStaleInProgressEvent() throws Exception {
        UUID eventId = UUID.randomUUID();
        assertTrue(claim(eventId));
        age(eventId, CLAIM_TIMEOUT.plusMinutes(1));

        assertEquals(1, claimConcurrently(eventId));
        // the new owner holds a fresh lease
        assertFalse(claim(eventId));
    }

    @Test
    void shouldLetWorkerBReclaimStaleEventAndTakeOverOwnership() throws Exception {
        UUID eventId = UUID.randomUUID();
        UUID workerA = UUID.randomUUID();
        UUID workerB = UUID.randomUUID();
        assertTrue(claim(eventId, workerA));
        assertEquals(workerA, leaseOwner(eventId));
        age(eventId, CLAIM_TIMEOUT.plusMinutes(1));

        assertTrue(claim(eventId, workerB));

        DeltaEventRecord record = repository.findById(eventId).orElseThrow();
        assertEquals(MigrationStatus.IN_PROGRESS, record.status());
        assertEquals(2, record.attempts());
        assertEquals(workerB, leaseOwner(eventId));
    }

    @Test
    void shouldNotLetStaleWorkerCompleteReclaimedEvent() throws Exception {
        UUID eventId = UUID.randomUUID();
        UUID workerA = UUID.randomUUID();
        UUID workerB = reclaimedFrom(workerA, eventId);

        assertFalse(repository.markCompleted(eventId, workerA));

        assertEquals(MigrationStatus.IN_PROGRESS, repository.findById(eventId).orElseThrow().status());
        assertEquals(workerB, leaseOwner(eventId));
    }

    @Test
    void shouldNotLetStaleWorkerFailReclaimedEvent() throws Exception {
        UUID eventId = UUID.randomUUID();
        UUID workerA = UUID.randomUUID();
        UUID workerB = reclaimedFrom(workerA, eventId);

        assertFalse(repository.markFailed(eventId, workerA, "late failure of stale worker"));

        DeltaEventRecord record = repository.findById(eventId).orElseThrow();
        assertEquals(MigrationStatus.IN_PROGRESS, record.status());
        assertNull(record.lastError());
        assertEquals(workerB, leaseOwner(eventId));
    }

    @Test
    void shouldLetNewOwnerCompleteReclaimedEvent() throws Exception {
        UUID eventId = UUID.randomUUID();
        UUID workerA = UUID.randomUUID();
        UUID workerB = reclaimedFrom(workerA, eventId);
        assertFalse(repository.markFailed(eventId, workerA, "late failure of stale worker"));

        assertTrue(repository.markCompleted(eventId, workerB));

        assertEquals(MigrationStatus.COMPLETED, repository.findById(eventId).orElseThrow().status());
        assertNull(leaseOwner(eventId));
    }

    @Test
    void shouldKeepCompletedTerminalForEveryWorker() throws Exception {
        UUID eventId = UUID.randomUUID();
        UUID workerA = UUID.randomUUID();
        UUID workerB = reclaimedFrom(workerA, eventId);
        assertTrue(repository.markCompleted(eventId, workerB));
        age(eventId, Duration.ofDays(1));

        assertFalse(repository.markFailed(eventId, workerA, "stale"));
        assertFalse(repository.markCompleted(eventId, workerA));
        assertFalse(repository.markFailed(eventId, workerB, "second finish"));
        assertFalse(claim(eventId, UUID.randomUUID()));
        assertEquals(0, claimConcurrently(eventId));

        DeltaEventRecord record = repository.findById(eventId).orElseThrow();
        assertEquals(MigrationStatus.COMPLETED, record.status());
        assertNull(record.lastError());
    }

    @Test
    void shouldLetOnlyCurrentOwnerFinishWhenStaleWorkersRace() throws Exception {
        UUID eventId = UUID.randomUUID();
        List<UUID> staleWorkers = new ArrayList<>();
        // a chain of owners whose leases each expired, then the current owner
        for (int i = 0; i < WORKERS - 1; i++) {
            UUID worker = UUID.randomUUID();
            assertTrue(claim(eventId, worker));
            age(eventId, CLAIM_TIMEOUT.plusMinutes(1));
            staleWorkers.add(worker);
        }
        UUID currentOwner = UUID.randomUUID();
        assertTrue(claim(eventId, currentOwner));

        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> staleResults = new ArrayList<>();
        Future<Boolean> ownerResult;
        try (ExecutorService executor = Executors.newFixedThreadPool(WORKERS)) {
            for (int i = 0; i < staleWorkers.size(); i++) {
                UUID worker = staleWorkers.get(i);
                boolean complete = i % 2 == 0;
                staleResults.add(executor.submit(() -> {
                    start.await();
                    return complete
                            ? repository.markCompleted(eventId, worker)
                            : repository.markFailed(eventId, worker, "stale");
                }));
            }
            ownerResult = executor.submit(() -> {
                start.await();
                return repository.markCompleted(eventId, currentOwner);
            });
            start.countDown();

            for (Future<Boolean> result : staleResults) {
                assertFalse(result.get());
            }
            assertTrue(ownerResult.get());
        }

        DeltaEventRecord record = repository.findById(eventId).orElseThrow();
        assertEquals(MigrationStatus.COMPLETED, record.status());
        assertEquals(WORKERS, record.attempts());
        assertNull(record.lastError());
    }

    @Test
    void shouldNotClaimEventIdRecordedForAnotherTenant() throws Exception {
        UUID eventId = UUID.randomUUID();
        UUID owner = UUID.randomUUID();
        assertTrue(claim(eventId, owner));
        assertTrue(repository.markFailed(eventId, owner, "boom"));

        assertFalse(repository.tryClaim(eventId, "tenant-2", CANDIDATE, OCCURRED_AT, UUID.randomUUID()));
        assertFalse(repository.tryClaim(eventId, TENANT, "candidate-2", OCCURRED_AT, UUID.randomUUID()));

        DeltaEventRecord record = repository.findById(eventId).orElseThrow();
        assertEquals(TENANT, record.tenantId());
        assertEquals(MigrationStatus.FAILED, record.status());
    }

    @Test
    void shouldNotOverwriteCompletedWithLateFailure() {
        UUID eventId = UUID.randomUUID();
        UUID owner = UUID.randomUUID();
        assertTrue(claim(eventId, owner));
        assertTrue(repository.markCompleted(eventId, owner));

        assertFalse(repository.markFailed(eventId, owner, "late"));

        DeltaEventRecord record = repository.findById(eventId).orElseThrow();
        assertEquals(MigrationStatus.COMPLETED, record.status());
        assertNull(record.lastError());
    }

    @Test
    void shouldTruncateLongError() {
        UUID eventId = UUID.randomUUID();
        UUID owner = UUID.randomUUID();
        assertTrue(claim(eventId, owner));

        assertTrue(repository.markFailed(eventId, owner, "x".repeat(5_000)));

        assertEquals(1_000, repository.findById(eventId).orElseThrow().lastError().length());
    }

    private boolean claim(UUID eventId) {
        return claim(eventId, UUID.randomUUID());
    }

    private boolean claim(UUID eventId, UUID leaseOwner) {
        return repository.tryClaim(eventId, TENANT, CANDIDATE, OCCURRED_AT, leaseOwner);
    }

    private UUID leaseOwner(UUID eventId) throws SQLException {
        String sql = "SELECT lease_owner FROM candidate_delta_event WHERE event_id = ?";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, eventId);
            try (ResultSet resultSet = statement.executeQuery()) {
                assertTrue(resultSet.next(), "row not found");
                return resultSet.getObject(1, UUID.class);
            }
        }
    }

    private UUID reclaimedFrom(UUID staleWorker, UUID eventId) throws SQLException {
        assertTrue(claim(eventId, staleWorker));
        age(eventId, CLAIM_TIMEOUT.plusMinutes(1));
        UUID newOwner = UUID.randomUUID();
        assertTrue(claim(eventId, newOwner));
        return newOwner;
    }

    private int claimConcurrently(UUID eventId) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> results = new ArrayList<>();

        try (ExecutorService executor = Executors.newFixedThreadPool(WORKERS)) {
            for (int i = 0; i < WORKERS; i++) {
                results.add(executor.submit(() -> {
                    start.await();
                    return claim(eventId);
                }));
            }
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

    private void age(UUID eventId, Duration age) throws SQLException {
        String sql = "UPDATE candidate_delta_event SET updated_at = now() - (? * INTERVAL '1 millisecond') WHERE event_id = ?";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, age.toMillis());
            statement.setObject(2, eventId);
            statement.executeUpdate();
        }
    }
}
