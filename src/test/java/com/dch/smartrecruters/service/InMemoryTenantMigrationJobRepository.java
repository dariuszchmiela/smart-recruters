package com.dch.smartrecruters.service;

import com.dch.smartrecruters.state.JobProgress;
import com.dch.smartrecruters.state.TenantMigrationJob;
import com.dch.smartrecruters.state.TenantMigrationJobRepository;
import com.dch.smartrecruters.state.TenantMigrationJobStatus;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Same state transitions as the JDBC repository, without lease expiry.
 * Records every checkpoint so tests can assert how the job advanced.
 */
class InMemoryTenantMigrationJobRepository implements TenantMigrationJobRepository {

    private final Map<UUID, TenantMigrationJob> jobs = new ConcurrentHashMap<>();
    private final Map<UUID, UUID> leaseOwners = new ConcurrentHashMap<>();
    private final Set<UUID> expiredLeases = ConcurrentHashMap.newKeySet();
    final List<Integer> checkpoints = new ArrayList<>();
    volatile boolean crashOnNextCheckpoint;

    TenantMigrationJob save(TenantMigrationJob job) {
        jobs.put(job.jobId(), job);
        return job;
    }

    TenantMigrationJob newJob(String tenantId, int pageSize, int nextPage) {
        return save(new TenantMigrationJob(
                UUID.randomUUID(), tenantId, TenantMigrationJobStatus.PENDING, pageSize, nextPage,
                0, 0, 0, 0, null, Instant.now(), Instant.now()
        ));
    }

    TenantMigrationJob get(UUID jobId) {
        return jobs.get(jobId);
    }

    @Override
    public synchronized TenantMigrationJob createOrGetUnfinished(String tenantId, int pageSize) {
        return jobs.values().stream()
                .filter(job -> job.tenantId().equals(tenantId))
                .filter(job -> job.status() == TenantMigrationJobStatus.PENDING
                        || job.status() == TenantMigrationJobStatus.RUNNING
                        || job.status() == TenantMigrationJobStatus.FAILED)
                .findFirst()
                .orElseGet(() -> newJob(tenantId, pageSize, 0));
    }

    @Override
    public Optional<TenantMigrationJob> findById(UUID jobId) {
        return Optional.ofNullable(jobs.get(jobId));
    }

    @Override
    public synchronized Optional<TenantMigrationJob> tryClaim(UUID jobId, UUID leaseOwner) {
        TenantMigrationJob job = jobs.get(jobId);
        boolean claimable = job != null
                && (job.status() == TenantMigrationJobStatus.PENDING
                || job.status() == TenantMigrationJobStatus.FAILED
                || (job.status() == TenantMigrationJobStatus.RUNNING && expiredLeases.remove(jobId)));
        if (!claimable) {
            return Optional.empty();
        }
        leaseOwners.put(jobId, leaseOwner);
        return Optional.of(save(withStatus(job, TenantMigrationJobStatus.RUNNING, null)));
    }

    @Override
    public synchronized boolean recordPage(UUID jobId, UUID leaseOwner, int nextPage, JobProgress progress) {
        TenantMigrationJob job = jobs.get(jobId);
        if (!holds(job, leaseOwner)) {
            return false;
        }
        if (crashOnNextCheckpoint) {
            crashOnNextCheckpoint = false;
            throw new SimulatedCrash();
        }
        checkpoints.add(nextPage);
        save(new TenantMigrationJob(
                job.jobId(), job.tenantId(), job.status(), job.pageSize(), nextPage,
                job.processedCount() + progress.processed(),
                job.succeededCount() + progress.succeeded(),
                job.skippedCount() + progress.skipped(),
                job.failedCount() + progress.failed(),
                job.lastError(), job.createdAt(), Instant.now()
        ));
        return true;
    }

    @Override
    public synchronized boolean complete(UUID jobId, UUID leaseOwner) {
        TenantMigrationJob job = jobs.get(jobId);
        if (!holds(job, leaseOwner)) {
            return false;
        }
        save(withStatus(job, job.failedCount() > 0
                ? TenantMigrationJobStatus.COMPLETED_WITH_ERRORS
                : TenantMigrationJobStatus.COMPLETED, null));
        return true;
    }

    @Override
    public synchronized boolean markFailed(UUID jobId, UUID leaseOwner, String error) {
        TenantMigrationJob job = jobs.get(jobId);
        if (!holds(job, leaseOwner)) {
            return false;
        }
        save(withStatus(job, TenantMigrationJobStatus.FAILED, error));
        return true;
    }

    @Override
    public List<UUID> findResumable(int limit) {
        return jobs.values().stream()
                .filter(job -> job.status() == TenantMigrationJobStatus.PENDING)
                .map(TenantMigrationJob::jobId)
                .limit(limit)
                .toList();
    }

    /**
     * Simulates another worker taking over the job (e.g. after this one's lease expired).
     */
    void stealLease(UUID jobId) {
        leaseOwners.put(jobId, UUID.randomUUID());
    }

    /**
     * Simulates the lease of a crashed worker running out, so the job can be claimed again.
     */
    void expireLease(UUID jobId) {
        expiredLeases.add(jobId);
    }

    private boolean holds(TenantMigrationJob job, UUID leaseOwner) {
        return job != null
                && job.status() == TenantMigrationJobStatus.RUNNING
                && leaseOwner.equals(leaseOwners.get(job.jobId()));
    }

    private static TenantMigrationJob withStatus(TenantMigrationJob job, TenantMigrationJobStatus status, String error) {
        return new TenantMigrationJob(
                job.jobId(), job.tenantId(), status, job.pageSize(), job.nextPage(),
                job.processedCount(), job.succeededCount(), job.skippedCount(), job.failedCount(),
                error, job.createdAt(), Instant.now()
        );
    }

    /**
     * The process dies: nothing after this point runs, not even the job's own error handling.
     */
    static final class SimulatedCrash extends Error {
    }
}
