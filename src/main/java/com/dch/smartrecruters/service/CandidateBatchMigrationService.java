package com.dch.smartrecruters.service;

import com.dch.smartrecruters.client.SapClient;
import com.dch.smartrecruters.client.sap.SapCandidate;
import com.dch.smartrecruters.client.sap.SapCandidatePage;
import com.dch.smartrecruters.state.JobProgress;
import com.dch.smartrecruters.state.TenantMigrationJob;
import com.dch.smartrecruters.state.TenantMigrationJobRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;

/**
 * Migrates all candidates of one tenant page by page:
 * <pre>
 * SAP page (checkpoint = next_page)
 *   -> at most {@code parallelism} candidates at a time
 *     -> CandidateMigrationService (claim, mapping, validation, target call, state)
 *   -> page counters + next checkpoint persisted in one statement
 * </pre>
 * Only one page is held in memory. A crash between "page processed" and "checkpoint saved"
 * re-processes that page on resume; the per-candidate claim makes this safe
 * (already COMPLETED candidates are not sent again).
 * <p>
 * Counters are added only together with the page checkpoint, so every page contributes exactly once:
 * <ul>
 *   <li>processed - candidates read from SAP; always succeeded + skipped + failed</li>
 *   <li>succeeded - candidate is COMPLETED in the target: migrated now, or already COMPLETED
 *       (earlier job, or a run that crashed before its checkpoint) - a replayed page counts the same</li>
 *   <li>skipped - held by another live worker at that moment, or taken over from this job's stale claim
 *       before it could be finished; its outcome is not known to this job</li>
 *   <li>failed - this attempt failed (validation, target, ...); the record is FAILED and retried by a later job</li>
 * </ul>
 */
public class CandidateBatchMigrationService {

    private static final Logger log = LoggerFactory.getLogger(CandidateBatchMigrationService.class);

    private final SapClient sapClient;
    private final CandidateMigrationService candidateMigrationService;
    private final TenantMigrationJobRepository jobRepository;
    private final int batchSize;
    private final int parallelism;

    public CandidateBatchMigrationService(
            SapClient sapClient,
            CandidateMigrationService candidateMigrationService,
            TenantMigrationJobRepository jobRepository,
            int batchSize,
            int parallelism
    ) {
        if (batchSize < 1) {
            throw new IllegalArgumentException("batchSize must be >= 1");
        }
        if (parallelism < 1) {
            throw new IllegalArgumentException("parallelism must be >= 1");
        }
        this.sapClient = sapClient;
        this.candidateMigrationService = candidateMigrationService;
        this.jobRepository = jobRepository;
        this.batchSize = batchSize;
        this.parallelism = parallelism;
    }

    /**
     * Returns a new PENDING job, or the tenant's unfinished one (which is then resumed from its checkpoint).
     */
    public TenantMigrationJob startOrResume(String tenantId) {
        return jobRepository.createOrGetUnfinished(tenantId, batchSize);
    }

    public Optional<TenantMigrationJob> findJob(UUID jobId) {
        return jobRepository.findById(jobId);
    }

    public List<UUID> findResumableJobs(int limit) {
        return jobRepository.findResumable(limit);
    }

    /**
     * Runs the job to the end in the calling thread. Does nothing when the job cannot be claimed
     * (finished, or held by another live worker).
     */
    public void runJob(UUID jobId) {
        UUID leaseOwner = UUID.randomUUID();
        Optional<TenantMigrationJob> claimed = jobRepository.tryClaim(jobId, leaseOwner);
        if (claimed.isEmpty()) {
            log.info("Migration job {} not claimed (finished or owned by another worker)", jobId);
            return;
        }

        TenantMigrationJob job = claimed.get();
        log.info("Migration job {} for tenant {} running from page {}", jobId, job.tenantId(), job.nextPage());

        try {
            if (processPages(job, leaseOwner) && jobRepository.complete(jobId, leaseOwner)) {
                log.info("Migration job {} for tenant {} finished", jobId, job.tenantId());
            }
        } catch (InterruptedException e) {
            // shutdown: the job stays RUNNING and is resumed from the checkpoint after its lease expires
            Thread.currentThread().interrupt();
            log.warn("Migration job {} interrupted", jobId);
        } catch (RuntimeException e) {
            log.error("Migration job {} for tenant {} failed", jobId, job.tenantId(), e);
            jobRepository.markFailed(jobId, leaseOwner, e.getMessage());
        }
    }

    /**
     * @return false when the lease was lost and the job must not be touched any more
     */
    private boolean processPages(TenantMigrationJob job, UUID leaseOwner) throws InterruptedException {
        int page = job.nextPage();

        while (true) {
            SapCandidatePage candidates = sapClient.getCandidates(job.tenantId(), page, job.pageSize());
            JobProgress progress = processPage(job.tenantId(), candidates.items());

            int nextPage = page + 1;

            if (!jobRepository.recordPage(job.jobId(), leaseOwner, nextPage, progress)) {
                log.warn("Migration job {} lost its lease at page {}, stopping", job.jobId(), page);
                return false;
            }

            log.info("Migration job {} page {} done: {}", job.jobId(), page, progress);

            // an empty page ends the job even if SAP claims there is more, to never loop forever
            if (!candidates.hasNext() || candidates.items().isEmpty()) {
                return true;
            }
            page = nextPage;
        }
    }

    /**
     * Virtual thread per candidate, but a new one is only started after a permit is free,
     * so at most {@code parallelism} candidates of the page are in flight at any time.
     * A failure of one candidate is counted and never stops the others.
     */
    JobProgress processPage(String tenantId, List<SapCandidate> candidates) throws InterruptedException {
        Semaphore permits = new Semaphore(parallelism);
        List<Future<CandidateResult>> results = new ArrayList<>(candidates.size());

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (SapCandidate candidate : candidates) {
                permits.acquire();
                try {
                    results.add(executor.submit(() -> {
                        try {
                            return migrate(tenantId, candidate);
                        } finally {
                            permits.release();
                        }
                    }));
                } catch (RuntimeException e) {
                    permits.release();
                    throw e;
                }
            }
        }

        long succeeded = 0;
        long skipped = 0;
        long failed = 0;
        for (Future<CandidateResult> result : results) {
            switch (outcome(result)) {
                case SUCCEEDED -> succeeded++;
                case SKIPPED -> skipped++;
                case FAILED -> failed++;
            }
        }
        return new JobProgress(candidates.size(), succeeded, skipped, failed);
    }

    private CandidateResult migrate(String tenantId, SapCandidate candidate) {
        try {
            return switch (candidateMigrationService.migrateCandidate(tenantId, candidate)) {
                case MIGRATED, ALREADY_MIGRATED -> CandidateResult.SUCCEEDED;
                case CLAIMED_BY_OTHER_WORKER, LEASE_LOST -> CandidateResult.SKIPPED;
            };
        } catch (RuntimeException e) {
            // the candidate record is already FAILED; a later job run retries it
            log.warn("Candidate {}:{} failed: {}", tenantId, candidate.id(), e.getMessage());
            return CandidateResult.FAILED;
        }
    }

    private static CandidateResult outcome(Future<CandidateResult> result) throws InterruptedException {
        try {
            return result.get();
        } catch (ExecutionException e) {
            // only Errors get here, RuntimeExceptions are turned into FAILED by migrate()
            throw new IllegalStateException("Candidate migration task crashed", e.getCause());
        }
    }

    private enum CandidateResult {
        SUCCEEDED,
        SKIPPED,
        FAILED
    }
}
