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
 * Runs reconciliation runs in the background, in the same style as {@link TenantMigrationJobLauncher}:
 * at most {@code maxConcurrentRuns} at a time, at most {@code queueCapacity} waiting. A run that does
 * not fit stays PENDING in the database. The recovery scan fails runs whose worker died (they are not
 * resumable) and starts PENDING ones.
 */
public class ReconciliationRunLauncher implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationRunLauncher.class);

    private final CandidateReconciliationService reconciliationService;
    private final ThreadPoolExecutor runExecutor;
    private final ScheduledExecutorService recoveryScheduler;
    private final Duration recoveryInterval;

    // runs queued or running in this instance, so the recovery scan does not queue them twice
    private final Set<UUID> localRuns = ConcurrentHashMap.newKeySet();

    public ReconciliationRunLauncher(
            CandidateReconciliationService reconciliationService,
            int maxConcurrentRuns,
            int queueCapacity,
            Duration recoveryInterval
    ) {
        if (maxConcurrentRuns < 1) {
            throw new IllegalArgumentException("maxConcurrentRuns must be >= 1");
        }
        if (queueCapacity < 1) {
            throw new IllegalArgumentException("queueCapacity must be >= 1");
        }
        this.reconciliationService = reconciliationService;
        this.recoveryInterval = recoveryInterval;
        this.runExecutor = new ThreadPoolExecutor(
                maxConcurrentRuns,
                maxConcurrentRuns,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queueCapacity),
                Thread.ofPlatform().name("reconciliation-run-", 0).factory(),
                new ThreadPoolExecutor.AbortPolicy()
        );
        this.recoveryScheduler = Executors.newSingleThreadScheduledExecutor(
                Thread.ofPlatform().name("reconciliation-run-recovery").daemon().factory()
        );
    }

    public void start() {
        recoveryScheduler.scheduleWithFixedDelay(
                this::recover,
                0,
                recoveryInterval.toMillis(),
                TimeUnit.MILLISECONDS
        );
    }

    /**
     * @return false when the queue is full; the run stays PENDING and is picked up later
     */
    public boolean submit(UUID runId) {
        if (!localRuns.add(runId)) {
            return true;
        }

        try {
            runExecutor.execute(() -> {
                try {
                    reconciliationService.runReconciliation(runId);
                } finally {
                    localRuns.remove(runId);
                }
            });
            return true;
        } catch (RejectedExecutionException e) {
            localRuns.remove(runId);
            log.warn("Reconciliation run {} not queued (executor full or stopped), left for the recovery scan", runId);
            return false;
        }
    }

    void recover() {
        try {
            int failed = reconciliationService.failAbandonedRuns();
            if (failed > 0) {
                log.warn("Marked {} abandoned reconciliation run(s) FAILED", failed);
            }

            int freeSlots = runExecutor.getQueue().remainingCapacity();
            if (freeSlots == 0) {
                return;
            }
            List<UUID> runIds = reconciliationService.findPendingRuns(freeSlots);
            for (UUID runId : runIds) {
                submit(runId);
            }
        } catch (RuntimeException e) {
            // never let one failed scan cancel the schedule
            log.warn("Reconciliation recovery scan failed: {}", e.getMessage());
        }
    }

    @Override
    public void close() throws InterruptedException {
        recoveryScheduler.shutdownNow();
        runExecutor.shutdownNow();
        if (!runExecutor.awaitTermination(30, TimeUnit.SECONDS)) {
            log.warn("Reconciliation runs did not stop in time; they will be failed after their lease expires");
        }
    }
}
