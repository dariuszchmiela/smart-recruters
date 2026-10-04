package com.dch.smartrecruters.service;

import java.util.UUID;

/**
 * This processing attempt lost its lease on the event: it was considered stale and another worker
 * reclaimed it, so this attempt may neither complete nor fail it. The outcome belongs to the new owner.
 * <p>
 * Retryable on purpose: the redelivered event is decided by the inbox state - COMPLETED by the new owner
 * is ignored as a duplicate, still IN_PROGRESS is retried later, FAILED is reclaimed and processed again.
 * Never acknowledged as success, and never dead-lettered directly by the stale worker.
 */
public class DeltaEventLeaseLostException extends RuntimeException {

    public DeltaEventLeaseLostException(UUID eventId, String stage) {
        super("Delta event " + eventId + " was reclaimed by another worker before this attempt could " + stage);
    }
}
