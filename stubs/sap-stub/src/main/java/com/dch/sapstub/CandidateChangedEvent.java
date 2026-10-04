package com.dch.sapstub;

import java.time.Instant;
import java.util.UUID;

/**
 * Published for every candidate change. Identity only - consumers read the current data via the API.
 */
public record CandidateChangedEvent(
        UUID eventId,
        String tenantId,
        String candidateId,
        Instant occurredAt
) {
}
