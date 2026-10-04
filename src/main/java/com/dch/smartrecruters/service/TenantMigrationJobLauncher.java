package com.dch.smartrecruters.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Runs tenant migration jobs in the background:
 * at most {@code maxConcurrentJobs} jobs at a time, at most {@code queueCapacity} waiting.
 * A job that does not fit stays PENDING in the database and is picked up by the recovery scan,
 * which also resumes RUNNING jobs whose owner died (expired lease), e.g. after a restart.
 */
public class TenantMigrationJobLauncher implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(TenantMigrationJobLauncher.class);

    private final CandidateBatchMigrationService batchMigrationService;
    private final ThreadPoolExecutor jobExecutor;
    private final ScheduledExecutorService recoveryScheduler;
    private final Duration recoveryInterval;

    // jobs queued or running in this instance, so the recovery scan does not queue them twice
    private final Set<UUID> localJobs = ConcurrentHashMap.newKeySet();

    public TenantMigrationJobLauncher(
            CandidateBatchMigrationService batchMigrationService,
            int maxConcurrentJobs,
            int queueCapacity,
            Duration recoveryInterval
    ) {
        if (maxConcurrentJobs < 1) {
            throw new IllegalArgumentException("maxConcurrentJobs must be >= 1");
        }
        if (queueCapacity < 1) {
            throw new IllegalArgumentException("queueCapacity must be >= 1");
        }
        this.batchMigrationService = batchMigrationService;
        this.recoveryInterval = recoveryInterval;
        this.jobExecutor = new ThreadPoolExecutor(
                maxConcurrentJobs,
                maxConcurrentJobs,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queueCapacity),
                Thread.ofPlatform().name("tenant-migration-job-", 0).factory(),
                new ThreadPoolExecutor.AbortPolicy()
        );
        this.recoveryScheduler = Executors.newSingleThreadScheduledExecutor(
                Thread.ofPlatform().name("tenant-migration-job-recovery").daemon().factory()
        );
    }

    /**
     * Starts the periodic recovery scan; the first scan runs right away, so jobs interrupted
     * by a restart continue as soon as their lease has expired.
     */
    public void start() {
        recoveryScheduler.scheduleWithFixedDelay(
                this::resumeAbandonedJobs,
                0,
                recoveryInterval.toMillis(),
                TimeUnit.MILLISECONDS
        );
    }

    /**
     * @return false when the queue is full; the job stays in the database and is picked up later
     */
    public boolean submit(UUID jobId) {
        if (!localJobs.add(jobId)) {
            return true;
        }

        try {
            jobExecutor.execute(() -> {
                try {
                    batchMigrationService.runJob(jobId);
                } finally {
                    localJobs.remove(jobId);
                }
            });
            return true;
        } catch (RejectedExecutionException e) {
            localJobs.remove(jobId);
            log.warn("Migration job {} not queued (executor full or stopped), left for the recovery scan", jobId);
            return false;
        }
    }

    void resumeAbandonedJobs() {
        try {
            int freeSlots = jobExecutor.getQueue().remainingCapacity();
            if (freeSlots == 0) {
                return;
            }

            List<UUID> jobIds = batchMigrationService.findResumableJobs(freeSlots);
            for (UUID jobId : jobIds) {
                submit(jobId);
            }
        } catch (RuntimeException e) {
            // never let one failed scan cancel the schedule
            log.warn("Migration job recovery scan failed: {}", e.getMessage());
        }
    }

    @Override
    public void close() throws InterruptedException {
        recoveryScheduler.shutdownNow();
        jobExecutor.shutdownNow();
        if (!jobExecutor.awaitTermination(30, TimeUnit.SECONDS)) {
            log.warn("Migration jobs did not stop in time; they will be resumed after their lease expires");
        }
    }
}
