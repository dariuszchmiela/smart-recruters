package com.dch.srstub;

import java.time.Instant;

public record StoredCandidate(
        String id,
        String tenantId,
        String externalId,
        String firstName,
        String lastName,
        String email,
        Instant createdAt,
        Instant updatedAt
) {
}
