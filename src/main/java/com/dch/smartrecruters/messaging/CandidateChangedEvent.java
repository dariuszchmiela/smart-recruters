package com.dch.smartrecruters.messaging;

import java.time.Instant;
import java.util.UUID;

/**
 * Kafka contract for "a candidate changed in the source system".
 * Carries only identity, never candidate data: the consumer always fetches the latest source state,
 * so out-of-date or reordered events still converge the target to the current source.
 *
 * @param eventId unique per change; idempotency key of the event
 */
public record CandidateChangedEvent(
        UUID eventId,
        String tenantId,
        String candidateId,
        Instant occurredAt
) {

    /**
     * Partition key: all changes of one candidate land on one partition and are processed in order.
     */
    public String partitionKey() {
        return tenantId + ":" + candidateId;
    }
}
