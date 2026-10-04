package com.dch.smartrecruters.state.jdbc;

import com.dch.smartrecruters.state.FingerprintedCandidate;
import com.dch.smartrecruters.state.ReconciliationItem;
import com.dch.smartrecruters.state.ReconciliationResult;
import com.dch.smartrecruters.state.ReconciliationRun;
import com.dch.smartrecruters.state.ReconciliationRunStatus;
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
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real PostgreSQL (Testcontainers) with the production schema applied by Flyway.
 * Skipped when no Docker-compatible runtime is available.
 */
@Testcontainers(disabledWithoutDocker = true)
class JdbcReconciliationRepositoryIntegrationTest {

    private static final int WORKERS = 20;
    private static final Duration LEASE_TIMEOUT = Duration.ofMinutes(10);
    private static final String TENANT = "tenant-1";

    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17");

    private static HikariDataSource dataSource;
    private static JdbcReconciliationRepository repository;

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

        repository = new JdbcReconciliationRepository(dataSource, LEASE_TIMEOUT);
    }

    @AfterAll
    static void closeDataSource() {
        dataSource.close();
    }

    @BeforeEach
    void cleanTables() throws SQLException {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("TRUNCATE reconciliation_run, candidate_reconciliation_item");
        }
    }

    @Test
    void shouldAllowOnlyOneUnfinishedRunPerTenantUnderConcurrentStarts() throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        List<Future<UUID>> results = new ArrayList<>();

        try (ExecutorService executor = Executors.newFixedThreadPool(WORKERS)) {
            for (int i = 0; i < WORKERS; i++) {
                results.add(executor.submit(() -> {
                    start.await();
                    return repository.createOrGetUnfinished(TENANT, 100).runId();
                }));
            }
            start.countDown();

            Set<UUID> runIds = new HashSet<>();
            for (Future<UUID> result : results) {
                runIds.add(result.get());
            }
            assertEquals(1, runIds.size());
        }
    }

    @Test
    void shouldReturnRunningRunInsteadOfCreatingSecond() {
        ReconciliationRun first = repository.createOrGetUnfinished(TENANT, 100);
        repository.tryClaim(first.runId(), UUID.randomUUID());

        ReconciliationRun again = repository.createOrGetUnfinished(TENANT, 100);

        assertEquals(first.runId(), again.runId());
        assertEquals(ReconciliationRunStatus.RUNNING, again.status());
    }

    @Test
    void shouldAllowNewRunAfterCompletedOrFailedRun() {
        ReconciliationRun completed = repository.createOrGetUnfinished(TENANT, 100);
        UUID owner = UUID.randomUUID();
        repository.tryClaim(completed.runId(), owner);
        assertTrue(repository.complete(completed.runId(), owner).isPresent());

        ReconciliationRun failed = repository.createOrGetUnfinished(TENANT, 100);
        assertNotEquals(completed.runId(), failed.runId());
        UUID failedOwner = UUID.randomUUID();
        repository.tryClaim(failed.runId(), failedOwner);
        assertTrue(repository.markFailed(failed.runId(), failedOwner, "SAP unavailable"));

        ReconciliationRun third = repository.createOrGetUnfinished(TENANT, 100);
        assertNotEquals(failed.runId(), third.runId());
        assertEquals(ReconciliationRunStatus.PENDING, third.status());
    }

    @Test
    void shouldKeepRunsOfDifferentTenantsIndependent() {
        ReconciliationRun first = repository.createOrGetUnfinished("tenant-1", 100);
        ReconciliationRun second = repository.createOrGetUnfinished("tenant-2", 100);

        assertNotEquals(first.runId(), second.runId());
    }

    @Test
    void shouldLetExactlyOneWorkerClaimPendingRun() throws Exception {
        ReconciliationRun run = repository.createOrGetUnfinished(TENANT, 100);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> results = new ArrayList<>();

        try (ExecutorService executor = Executors.newFixedThreadPool(WORKERS)) {
            for (int i = 0; i < WORKERS; i++) {
                results.add(executor.submit(() -> {
                    start.await();
                    return repository.tryClaim(run.runId(), UUID.randomUUID()).isPresent();
                }));
            }
            start.countDown();

            int claimed = 0;
            for (Future<Boolean> result : results) {
                if (result.get()) {
                    claimed++;
                }
            }
            assertEquals(1, claimed);
        }
    }

    @Test
    void shouldNotLetForeignOwnerHeartbeatCompleteOrFailRun() {
        ReconciliationRun run = repository.createOrGetUnfinished(TENANT, 100);
        UUID owner = UUID.randomUUID();
        repository.tryClaim(run.runId(), owner);
        UUID stranger = UUID.randomUUID();

        assertFalse(repository.heartbeat(run.runId(), stranger));
        assertTrue(repository.complete(run.runId(), stranger).isEmpty());
        assertFalse(repository.markFailed(run.runId(), stranger, "late"));

        assertEquals(ReconciliationRunStatus.RUNNING, repository.findById(run.runId()).orElseThrow().status());
        assertTrue(repository.heartbeat(run.runId(), owner));
    }

    @Test
    void shouldFailAbandonedRunAndFenceOutItsWorker() throws SQLException {
        ReconciliationRun abandoned = repository.createOrGetUnfinished("tenant-abandoned", 100);
        UUID deadWorker = UUID.randomUUID();
        repository.tryClaim(abandoned.runId(), deadWorker);
        age(abandoned.runId(), LEASE_TIMEOUT.plusMinutes(1));

        ReconciliationRun alive = repository.createOrGetUnfinished("tenant-alive", 100);
        UUID liveWorker = UUID.randomUUID();
        repository.tryClaim(alive.runId(), liveWorker);

        assertEquals(1, repository.failAbandoned());

        ReconciliationRun failed = repository.findById(abandoned.runId()).orElseThrow();
        assertEquals(ReconciliationRunStatus.FAILED, failed.status());
        assertTrue(failed.lastError().startsWith("Abandoned"));
        // the worker coming back can no longer touch it, and the tenant can start a new run
        assertFalse(repository.heartbeat(abandoned.runId(), deadWorker));
        assertTrue(repository.complete(abandoned.runId(), deadWorker).isEmpty());
        assertNotEquals(abandoned.runId(), repository.createOrGetUnfinished("tenant-abandoned", 100).runId());
        assertEquals(ReconciliationRunStatus.RUNNING, repository.findById(alive.runId()).orElseThrow().status());
    }

    @Test
    void shouldFindOnlyPendingRuns() {
        ReconciliationRun pending = repository.createOrGetUnfinished("tenant-pending", 100);
        ReconciliationRun running = repository.createOrGetUnfinished("tenant-running", 100);
        repository.tryClaim(running.runId(), UUID.randomUUID());

        assertEquals(List.of(pending.runId()), repository.findPending(10));
    }

    @Test
    void shouldUpsertPagesIdempotentlyAndKeepSidesIndependent() {
        ReconciliationRun run = repository.createOrGetUnfinished(TENANT, 100);
        UUID owner = UUID.randomUUID();
        repository.tryClaim(run.runId(), owner);
        List<FingerprintedCandidate> page = List.of(
                new FingerprintedCandidate("c1", "a".repeat(64)),
                new FingerprintedCandidate("c2", "b".repeat(64))
        );

        repository.recordSource(run.runId(), TENANT, page);
        // the same page read again (e.g. shifted offset) does not create duplicates
        repository.recordSource(run.runId(), TENANT, page);
        repository.recordTarget(run.runId(), TENANT, List.of(new FingerprintedCandidate("c1", "a".repeat(64))));
        repository.recordTarget(run.runId(), TENANT, List.of());

        ReconciliationRun completed = repository.complete(run.runId(), owner).orElseThrow();
        assertEquals(2, completed.sourceCount());
        assertEquals(1, completed.targetCount());
        assertEquals(1, completed.matchedCount());
        assertEquals(1, completed.missingInTargetCount());
        assertEquals(List.of(new ReconciliationItem("c2", ReconciliationResult.MISSING_IN_TARGET, "b".repeat(64), null)),
                repository.findItems(run.runId(), null, 0, 10).items());
        assertEquals(List.of("c1"), repository.findItems(run.runId(), ReconciliationResult.MATCHED, 0, 10)
                .items().stream().map(ReconciliationItem::externalId).toList());
    }

    @Test
    void shouldNeverMixItemsOfDifferentRuns() {
        ReconciliationRun first = repository.createOrGetUnfinished("tenant-1", 100);
        ReconciliationRun second = repository.createOrGetUnfinished("tenant-2", 100);
        UUID firstOwner = UUID.randomUUID();
        UUID secondOwner = UUID.randomUUID();
        repository.tryClaim(first.runId(), firstOwner);
        repository.tryClaim(second.runId(), secondOwner);

        // same externalId in both tenants
        repository.recordSource(first.runId(), "tenant-1", List.of(new FingerprintedCandidate("c1", "a".repeat(64))));
        repository.recordTarget(second.runId(), "tenant-2", List.of(new FingerprintedCandidate("c1", "a".repeat(64))));

        ReconciliationRun firstDone = repository.complete(first.runId(), firstOwner).orElseThrow();
        ReconciliationRun secondDone = repository.complete(second.runId(), secondOwner).orElseThrow();
        assertEquals(1, firstDone.missingInTargetCount());
        assertEquals(0, firstDone.matchedCount());
        assertEquals(1, secondDone.unexpectedInTargetCount());
        assertEquals(0, secondDone.matchedCount());
    }

    private void age(UUID runId, Duration age) throws SQLException {
        String sql = "UPDATE reconciliation_run SET updated_at = now() - (? * INTERVAL '1 millisecond') WHERE run_id = ?";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, age.toMillis());
            statement.setObject(2, runId);
            statement.executeUpdate();
        }
    }
}
