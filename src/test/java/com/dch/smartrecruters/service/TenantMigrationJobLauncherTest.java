package com.dch.smartrecruters.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TenantMigrationJobLauncherTest {

    private final CandidateBatchMigrationService batchMigrationService = mock(CandidateBatchMigrationService.class);
    private final CountDownLatch release = new CountDownLatch(1);
    private TenantMigrationJobLauncher launcher;

    @AfterEach
    void tearDown() throws InterruptedException {
        release.countDown();
        launcher.close();
    }

    @Test
    void shouldRunSubmittedJobInBackground() {
        launcher = new TenantMigrationJobLauncher(batchMigrationService, 1, 1, Duration.ofMinutes(1));
        UUID jobId = UUID.randomUUID();

        assertTrue(launcher.submit(jobId));

        verify(batchMigrationService, timeout(5_000)).runJob(jobId);
    }

    @Test
    void shouldRejectJobsBeyondConcurrencyAndQueueCapacity() throws InterruptedException {
        launcher = new TenantMigrationJobLauncher(batchMigrationService, 1, 1, Duration.ofMinutes(1));
        CountDownLatch running = blockRunningJobs();

        assertTrue(launcher.submit(UUID.randomUUID()));
        assertTrue(running.await(5, TimeUnit.SECONDS));
        assertTrue(launcher.submit(UUID.randomUUID()));

        // one running + one queued: the third stays PENDING in the database for the recovery scan
        assertFalse(launcher.submit(UUID.randomUUID()));
    }

    @Test
    void shouldNotQueueSameJobTwice() throws InterruptedException {
        launcher = new TenantMigrationJobLauncher(batchMigrationService, 1, 5, Duration.ofMinutes(1));
        CountDownLatch running = blockRunningJobs();
        UUID jobId = UUID.randomUUID();

        launcher.submit(jobId);
        assertTrue(running.await(5, TimeUnit.SECONDS));
        launcher.submit(jobId);
        release.countDown();

        verify(batchMigrationService, timeout(5_000).times(1)).runJob(jobId);
        Thread.sleep(100);
        verify(batchMigrationService, times(1)).runJob(jobId);
    }

    @Test
    void shouldResumeJobsFoundByRecoveryScan() {
        UUID abandoned = UUID.randomUUID();
        UUID pending = UUID.randomUUID();
        when(batchMigrationService.findResumableJobs(any(Integer.class))).thenReturn(List.of(abandoned, pending));
        launcher = new TenantMigrationJobLauncher(batchMigrationService, 2, 10, Duration.ofMinutes(1));

        launcher.start();

        verify(batchMigrationService, timeout(5_000)).runJob(abandoned);
        verify(batchMigrationService, timeout(5_000)).runJob(pending);
    }

    private CountDownLatch blockRunningJobs() {
        CountDownLatch running = new CountDownLatch(1);
        doAnswer(invocation -> {
            running.countDown();
            release.await();
            return null;
        }).when(batchMigrationService).runJob(any());
        return running;
    }
}
