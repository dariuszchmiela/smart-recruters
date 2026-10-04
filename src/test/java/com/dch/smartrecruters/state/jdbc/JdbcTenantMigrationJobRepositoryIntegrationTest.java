package com.dch.smartrecruters.state.jdbc;

import com.dch.smartrecruters.state.JobProgress;
import com.dch.smartrecruters.state.TenantMigrationJob;
import com.dch.smartrecruters.state.TenantMigrationJobStatus;
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
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real PostgreSQL (Testcontainers) with the production schema applied by Flyway.
 * Skipped when no Docker-compatible runtime is available.
 */
@Testcontainers(disabledWithoutDocker = true)
class JdbcTenantMigrationJobRepositoryIntegrationTest {

    private static final int WORKERS = 20;
    private static final Duration LEASE_TIMEOUT = Duration.ofMinutes(10);
    private static final String TENANT = "tenant-1";

    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17");

    private static HikariDataSource dataSource;
    private static JdbcTenantMigrationJobRepository repository;

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

        repository = new JdbcTenantMigrationJobRepository(dataSource, LEASE_TIMEOUT);
    }

    @AfterAll
    static void closeDataSource() {
        dataSource.close();
    }

    @BeforeEach
    void cleanTable() throws SQLException {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("TRUNCATE tenant_migration_job");
        }
    }

    @Test
    void shouldCreatePendingJob() {
        TenantMigrationJob job = repository.createOrGetUnfinished(TENANT, 100);

        assertEquals(TENANT, job.tenantId());
        assertEquals(TenantMigrationJobStatus.PENDING, job.status());
        assertEquals(100, job.pageSize());
        assertEquals(0, job.nextPage());
        assertEquals(0, job.processedCount());
        assertEquals(Optional.of(job), repository.findById(job.jobId()));
    }

    @Test
    void shouldReturnSameUnfinishedJobForConcurrentStarts() throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        List<Future<UUID>> results = new ArrayList<>();

        try (ExecutorService executor = Executors.newFixedThreadPool(WORKERS)) {
            for (int i = 0; i < WORKERS; i++) {
                results.add(executor.submit(() -> {
                    start.await();
                    return repository.createOrGetUnfinished(TENANT, 100).jobId();
                }));
            }
            start.countDown();

            Set<UUID> jobIds = new HashSet<>();
            for (Future<UUID> result : results) {
                jobIds.add(result.get());
            }
            assertEquals(1, jobIds.size());
        }
    }

    @Test
    void shouldReturnFailedJobInsteadOfCreatingNewOne() {
        TenantMigrationJob job = repository.createOrGetUnfinished(TENANT, 100);
        UUID owner = UUID.randomUUID();
        repository.tryClaim(job.jobId(), owner);
        repository.markFailed(job.jobId(), owner, "SAP unavailable");

        assertEquals(job.jobId(), repository.createOrGetUnfinished(TENANT, 100).jobId());
    }

    @Test
    void shouldCreateNewJobAfterPreviousOneFinished() {
        TenantMigrationJob first = repository.createOrGetUnfinished(TENANT, 100);
        UUID owner = UUID.randomUUID();
        repository.tryClaim(first.jobId(), owner);
        repository.complete(first.jobId(), owner);

        TenantMigrationJob second = repository.createOrGetUnfinished(TENANT, 100);

        assertNotEquals(first.jobId(), second.jobId());
        assertEquals(TenantMigrationJobStatus.PENDING, second.status());
    }

    @Test
    void shouldKeepJobsOfDifferentTenantsSeparate() {
        TenantMigrationJob first = repository.createOrGetUnfinished("tenant-1", 100);
        TenantMigrationJob second = repository.createOrGetUnfinished("tenant-2", 100);

        assertNotEquals(first.jobId(), second.jobId());
    }

    @Test
    void shouldLetExactlyOneWorkerClaimJob() throws Exception {
        TenantMigrationJob job = repository.createOrGetUnfinished(TENANT, 100);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> results = new ArrayList<>();

        try (ExecutorService executor = Executors.newFixedThreadPool(WORKERS)) {
            for (int i = 0; i < WORKERS; i++) {
                results.add(executor.submit(() -> {
                    start.await();
                    return repository.tryClaim(job.jobId(), UUID.randomUUID()).isPresent();
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
        assertEquals(TenantMigrationJobStatus.RUNNING, repository.findById(job.jobId()).orElseThrow().status());
    }

    @Test
    void shouldNotClaimRunningJobWithFreshLease() {
        TenantMigrationJob job = repository.createOrGetUnfinished(TENANT, 100);
        repository.tryClaim(job.jobId(), UUID.randomUUID());

        assertTrue(repository.tryClaim(job.jobId(), UUID.randomUUID()).isEmpty());
    }

    @Test
    void shouldReclaimRunningJobWithExpiredLeaseAndFenceOutPreviousOwner() throws SQLException {
        TenantMigrationJob job = repository.createOrGetUnfinished(TENANT, 100);
        UUID crashed = UUID.randomUUID();
        repository.tryClaim(job.jobId(), crashed);
        repository.recordPage(job.jobId(), crashed, 4, new JobProgress(400, 390, 0, 10));
        age(job.jobId(), LEASE_TIMEOUT.plusMinutes(1));

        UUID resumed = UUID.randomUUID();
        TenantMigrationJob claimed = repository.tryClaim(job.jobId(), resumed).orElseThrow();

        // resumes from the checkpoint with the counters of the crashed run
        assertEquals(4, claimed.nextPage());
        assertEquals(400, claimed.processedCount());
        // the old owner can no longer move the job
        assertFalse(repository.recordPage(job.jobId(), crashed, 5, new JobProgress(100, 100, 0, 0)));
        assertFalse(repository.complete(job.jobId(), crashed));
        assertTrue(repository.recordPage(job.jobId(), resumed, 5, new JobProgress(100, 100, 0, 0)));
    }

    @Test
    void shouldAccumulateCountersAndAdvanceCheckpoint() {
        TenantMigrationJob job = repository.createOrGetUnfinished(TENANT, 100);
        UUID owner = UUID.randomUUID();
        repository.tryClaim(job.jobId(), owner);

        assertTrue(repository.recordPage(job.jobId(), owner, 1, new JobProgress(100, 97, 1, 2)));
        assertTrue(repository.recordPage(job.jobId(), owner, 2, new JobProgress(50, 50, 0, 0)));

        TenantMigrationJob updated = repository.findById(job.jobId()).orElseThrow();
        assertEquals(2, updated.nextPage());
        assertEquals(150, updated.processedCount());
        assertEquals(147, updated.succeededCount());
        assertEquals(1, updated.skippedCount());
        assertEquals(2, updated.failedCount());
        assertTrue(updated.updatedAt().isAfter(job.updatedAt()));
    }

    @Test
    void shouldCompleteWithoutErrors() {
        UUID jobId = finishWithFailures(0);

        assertEquals(TenantMigrationJobStatus.COMPLETED, repository.findById(jobId).orElseThrow().status());
    }

    @Test
    void shouldCompleteWithErrorsWhenAnyCandidateFailed() {
        UUID jobId = finishWithFailures(3);

        assertEquals(
                TenantMigrationJobStatus.COMPLETED_WITH_ERRORS,
                repository.findById(jobId).orElseThrow().status()
        );
    }

    @Test
    void shouldResumeFailedJobFromCheckpointAndClearError() {
        TenantMigrationJob job = repository.createOrGetUnfinished(TENANT, 100);
        UUID owner = UUID.randomUUID();
        repository.tryClaim(job.jobId(), owner);
        repository.recordPage(job.jobId(), owner, 3, new JobProgress(300, 300, 0, 0));
        repository.markFailed(job.jobId(), owner, "x".repeat(5_000));

        TenantMigrationJob failed = repository.findById(job.jobId()).orElseThrow();
        assertEquals(TenantMigrationJobStatus.FAILED, failed.status());
        assertEquals(1_000, failed.lastError().length());

        TenantMigrationJob resumed = repository.tryClaim(job.jobId(), UUID.randomUUID()).orElseThrow();
        assertEquals(TenantMigrationJobStatus.RUNNING, resumed.status());
        assertEquals(3, resumed.nextPage());
        assertNull(resumed.lastError());
    }

    @Test
    void shouldNeverClaimFinishedJob() {
        UUID jobId = finishWithFailures(1);

        assertTrue(repository.tryClaim(jobId, UUID.randomUUID()).isEmpty());
    }

    @Test
    void shouldFindPendingAndAbandonedJobsOnly() throws SQLException {
        TenantMigrationJob pending = repository.createOrGetUnfinished("tenant-pending", 100);

        TenantMigrationJob abandoned = repository.createOrGetUnfinished("tenant-abandoned", 100);
        repository.tryClaim(abandoned.jobId(), UUID.randomUUID());
        age(abandoned.jobId(), LEASE_TIMEOUT.plusMinutes(1));

        TenantMigrationJob running = repository.createOrGetUnfinished("tenant-running", 100);
        repository.tryClaim(running.jobId(), UUID.randomUUID());

        TenantMigrationJob failed = repository.createOrGetUnfinished("tenant-failed", 100);
        UUID owner = UUID.randomUUID();
        repository.tryClaim(failed.jobId(), owner);
        repository.markFailed(failed.jobId(), owner, "SAP unavailable");

        assertEquals(Set.of(pending.jobId(), abandoned.jobId()), Set.copyOf(repository.findResumable(10)));
        assertEquals(1, repository.findResumable(1).size());
    }

    private UUID finishWithFailures(long failed) {
        TenantMigrationJob job = repository.createOrGetUnfinished(TENANT, 100);
        UUID owner = UUID.randomUUID();
        repository.tryClaim(job.jobId(), owner);
        repository.recordPage(job.jobId(), owner, 1, new JobProgress(10, 10 - failed, 0, failed));
        assertTrue(repository.complete(job.jobId(), owner));
        return job.jobId();
    }

    private void age(UUID jobId, Duration age) throws SQLException {
        String sql = "UPDATE tenant_migration_job SET updated_at = now() - (? * INTERVAL '1 millisecond') WHERE job_id = ?";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, age.toMillis());
            statement.setObject(2, jobId);
            statement.executeUpdate();
        }
    }
}
