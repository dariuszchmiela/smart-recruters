package com.dch.smartrecruters.service;

import com.dch.smartrecruters.client.SapClient;
import com.dch.smartrecruters.client.SmartRecruitersClient;
import com.dch.smartrecruters.client.sap.SapCandidate;
import com.dch.smartrecruters.client.sap.SapCandidatePage;
import com.dch.smartrecruters.client.smartrecruiters.SmartRecruitersCandidate;
import com.dch.smartrecruters.client.smartrecruiters.SmartRecruitersCandidatePage;
import com.dch.smartrecruters.mapper.CandidateMapper;
import com.dch.smartrecruters.state.FingerprintedCandidate;
import com.dch.smartrecruters.state.ReconciliationItemPage;
import com.dch.smartrecruters.state.ReconciliationRepository;
import com.dch.smartrecruters.state.ReconciliationResult;
import com.dch.smartrecruters.state.ReconciliationRun;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Independent verification of one tenant: reads SAP and SmartRecruiters independently and compares them.
 * <ul>
 *   <li>Each SAP candidate is mapped into the target-owned field shape (externalId, firstName, lastName,
 *       email) with {@link CandidateMapper}; target candidates are read as stored.</li>
 *   <li>Both sides are compared by {@link CandidateFingerprint}: equal fingerprints are treated as equivalence
 *       under its canonicalization rules (NFC, stripped whitespace, null == blank, case-sensitive), with
 *       SHA-256 collisions considered negligible - not exact character-for-character equality of the
 *       original strings.</li>
 *   <li>{@code CandidateValidator} is intentionally not applied. Reconciliation verifies presence and content
 *       independently of migration eligibility, so it is not a replay of the migration decision pipeline:
 *       a source candidate that the migration would reject (e.g. missing email) is reported as
 *       MISSING_IN_TARGET when the target does not have it, but as MATCHED when an equivalent candidate
 *       already exists in the target anyway.</li>
 *   <li>candidate_migration (what the migration engine believes happened) is never consulted.</li>
 *   <li>Neither system is written to - both clients are only used for paged reads.</li>
 * </ul>
 * <pre>
 * claim run (PENDING -> RUNNING)
 *   -> SAP pages:            expected target fingerprint per candidate -> working table (batch upsert)
 *   -> SmartRecruiters pages: actual fingerprint per candidate         -> working table (batch upsert)
 *   -> PostgreSQL classifies every item and computes the counters      -> COMPLETED
 * any failure -> FAILED (working rows kept for diagnosis); a new run starts from scratch
 * </pre>
 * Only one page is held in memory; source and target meet in PostgreSQL.
 * <p>
 * Consistency: an eventually-consistent verification pass, not a snapshot. Delta synchronization
 * keeps running; a candidate changed during the scan may show up as a transient mismatch, which a
 * repeated run after the delta backlog is drained resolves.
 */
public class CandidateReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(CandidateReconciliationService.class);

    private final SapClient sapClient;
    private final SmartRecruitersClient smartRecruitersClient;
    private final CandidateMapper mapper;
    private final ReconciliationRepository repository;
    private final int pageSize;

    public CandidateReconciliationService(
            SapClient sapClient,
            SmartRecruitersClient smartRecruitersClient,
            CandidateMapper mapper,
            ReconciliationRepository repository,
            int pageSize
    ) {
        if (pageSize < 1) {
            throw new IllegalArgumentException("pageSize must be >= 1");
        }
        this.sapClient = sapClient;
        this.smartRecruitersClient = smartRecruitersClient;
        this.mapper = mapper;
        this.repository = repository;
        this.pageSize = pageSize;
    }

    /**
     * Creates a PENDING run, or returns the tenant's unfinished run (at most one per tenant).
     */
    public ReconciliationRun startOrGetUnfinished(String tenantId) {
        return repository.createOrGetUnfinished(tenantId, pageSize);
    }

    public Optional<ReconciliationRun> findRun(UUID runId) {
        return repository.findById(runId);
    }

    public ReconciliationItemPage findItems(UUID runId, ReconciliationResult result, int page, int size) {
        return repository.findItems(runId, result, page, size);
    }

    public List<UUID> findPendingRuns(int limit) {
        return repository.findPending(limit);
    }

    public int failAbandonedRuns() {
        return repository.failAbandoned();
    }

    /**
     * Executes the run in the calling thread. Does nothing when the run cannot be claimed.
     */
    public void runReconciliation(UUID runId) {
        UUID leaseOwner = UUID.randomUUID();
        Optional<ReconciliationRun> claimed = repository.tryClaim(runId, leaseOwner);
        if (claimed.isEmpty()) {
            log.info("Reconciliation run {} not claimed (not PENDING)", runId);
            return;
        }

        ReconciliationRun run = claimed.get();
        log.info("Reconciliation run {} for tenant {} started", runId, run.tenantId());

        try {
            if (!scanSource(run, leaseOwner) || !scanTarget(run, leaseOwner)) {
                return;
            }

            Optional<ReconciliationRun> completed = repository.complete(runId, leaseOwner);
            if (completed.isEmpty()) {
                log.warn("Reconciliation run {} was taken away before it could be completed", runId);
                return;
            }

            ReconciliationRun summary = completed.get();
            log.info("Reconciliation run {} for tenant {} completed: source={} target={} matched={} "
                            + "missingInTarget={} unexpectedInTarget={} mismatched={}",
                    runId, summary.tenantId(), summary.sourceCount(), summary.targetCount(),
                    summary.matchedCount(), summary.missingInTargetCount(),
                    summary.unexpectedInTargetCount(), summary.mismatchedCount());
        } catch (RuntimeException e) {
            log.error("Reconciliation run {} for tenant {} failed: {}", runId, run.tenantId(), e.getMessage(), e);
            repository.markFailed(runId, leaseOwner, e.getMessage());
        }
    }

    /**
     * @return false when the run is no longer held by this worker
     */
    private boolean scanSource(ReconciliationRun run, UUID leaseOwner) {
        int page = 0;
        long scanned = 0;

        while (true) {
            SapCandidatePage candidates = sapClient.getCandidates(run.tenantId(), page, run.pageSize());
            List<FingerprintedCandidate> fingerprints = candidates.items().stream()
                    .map(candidate -> expectedInTarget(run.tenantId(), candidate))
                    .toList();
            repository.recordSource(run.runId(), run.tenantId(), fingerprints);
            scanned += fingerprints.size();

            if (!repository.heartbeat(run.runId(), leaseOwner)) {
                log.warn("Reconciliation run {} lost during source scan at page {}", run.runId(), page);
                return false;
            }
            // an empty page ends the scan even if the source claims there is more
            if (!candidates.hasNext() || candidates.items().isEmpty()) {
                break;
            }
            page++;
        }

        log.info("Reconciliation run {} tenant {}: source scan complete, {} candidates in {} pages",
                run.runId(), run.tenantId(), scanned, page + 1);
        return true;
    }

    private boolean scanTarget(ReconciliationRun run, UUID leaseOwner) {
        int page = 0;
        long scanned = 0;

        while (true) {
            SmartRecruitersCandidatePage candidates =
                    smartRecruitersClient.getCandidates(run.tenantId(), page, run.pageSize());
            List<FingerprintedCandidate> fingerprints = candidates.items().stream()
                    .map(CandidateReconciliationService::actualInTarget)
                    .toList();
            repository.recordTarget(run.runId(), run.tenantId(), fingerprints);
            scanned += fingerprints.size();

            if (!repository.heartbeat(run.runId(), leaseOwner)) {
                log.warn("Reconciliation run {} lost during target scan at page {}", run.runId(), page);
                return false;
            }
            if (!candidates.hasNext() || candidates.items().isEmpty()) {
                break;
            }
            page++;
        }

        log.info("Reconciliation run {} tenant {}: target scan complete, {} candidates in {} pages",
                run.runId(), run.tenantId(), scanned, page + 1);
        return true;
    }

    /**
     * Maps the SAP candidate into the target-owned field shape (same {@link CandidateMapper} as the migration),
     * then fingerprints it using the same canonicalization used for the target side.
     * Validation is deliberately not applied: the result says whether source and target fingerprints are
     * equal (treated as equivalence), not whether the migration would have accepted the candidate.
     */
    private FingerprintedCandidate expectedInTarget(String tenantId, SapCandidate candidate) {
        if (!tenantId.equals(candidate.tenantId())) {
            throw new IllegalStateException("SAP returned candidate " + candidate.id()
                    + " of tenant " + candidate.tenantId() + " while scanning tenant " + tenantId);
        }
        requireId(candidate.id(), "SAP");
        var expected = mapper.mapToRequest(mapper.map(candidate));
        return new FingerprintedCandidate(expected.externalId(), CandidateFingerprint.of(expected));
    }

    private static FingerprintedCandidate actualInTarget(SmartRecruitersCandidate candidate) {
        requireId(candidate.externalId(), "SmartRecruiters");
        return new FingerprintedCandidate(candidate.externalId(), CandidateFingerprint.of(candidate));
    }

    private static void requireId(String id, String system) {
        if (id == null || id.isBlank()) {
            throw new IllegalStateException(system + " returned a candidate without id");
        }
    }
}
