package com.dch.smartrecruters.state;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Inbox of delta events, keyed by eventId. Same lease philosophy as {@link MigrationRecordRepository}.
 */
public interface CandidateDeltaEventRepository {

    /**
     * Atomic claim: unknown event -> IN_PROGRESS; FAILED or abandoned IN_PROGRESS -> IN_PROGRESS,
     * in every case owned by {@code leaseOwner} (one token per processing attempt).
     * COMPLETED, fresh IN_PROGRESS, or an eventId already recorded for a different tenant/candidate
     * -> no change, returns false.
     */
    boolean tryClaim(UUID eventId, String tenantId, String candidateId, Instant occurredAt, UUID leaseOwner);

    Optional<DeltaEventRecord> findById(UUID eventId);

    /**
     * @return false when {@code leaseOwner} no longer holds the event (lease reclaimed by another worker)
     */
    boolean markCompleted(UUID eventId, UUID leaseOwner);

    /**
     * @return false when {@code leaseOwner} no longer holds the event (lease reclaimed by another worker)
     */
    boolean markFailed(UUID eventId, UUID leaseOwner, String error);
}
