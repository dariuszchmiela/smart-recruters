package com.dch.smartrecruters.state;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Runs and their working items. Everything a run writes is keyed by its runId, so runs never share state.
 * Methods taking a {@code leaseOwner} only act while that owner holds the run in RUNNING;
 * {@code false} / empty means the run is no longer this worker's and it must stop.
 */
public interface ReconciliationRepository {

    /**
     * Creates a PENDING run, or returns the tenant's unfinished (PENDING / RUNNING) run.
     */
    ReconciliationRun createOrGetUnfinished(String tenantId, int pageSize);

    Optional<ReconciliationRun> findById(UUID runId);

    /**
     * PENDING -> RUNNING owned by {@code leaseOwner}.
     */
    Optional<ReconciliationRun> tryClaim(UUID runId, UUID leaseOwner);

    /**
     * Upserts source presence + fingerprint of one page (JDBC batch).
     */
    void recordSource(UUID runId, String tenantId, List<FingerprintedCandidate> candidates);

    /**
     * Upserts target presence + fingerprint of one page (JDBC batch).
     */
    void recordTarget(UUID runId, String tenantId, List<FingerprintedCandidate> candidates);

    /**
     * Renews the lease after a page; false when the run is no longer held by {@code leaseOwner}.
     */
    boolean heartbeat(UUID runId, UUID leaseOwner);

    /**
     * Classifies every item of the run and stores the summary counters, in one transaction;
     * RUNNING -> COMPLETED.
     */
    Optional<ReconciliationRun> complete(UUID runId, UUID leaseOwner);

    boolean markFailed(UUID runId, UUID leaseOwner, String error);

    /**
     * RUNNING runs without a heartbeat for longer than the lease timeout -> FAILED.
     *
     * @return number of runs failed
     */
    int failAbandoned();

    List<UUID> findPending(int limit);

    /**
     * Classified items of a run ordered by externalId; {@code result == null} means every difference
     * (everything except MATCHED).
     */
    ReconciliationItemPage findItems(UUID runId, ReconciliationResult result, int page, int size);
}
