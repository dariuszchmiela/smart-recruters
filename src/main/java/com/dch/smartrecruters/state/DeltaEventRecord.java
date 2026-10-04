package com.dch.smartrecruters.state;

import java.util.UUID;

public record DeltaEventRecord(
        UUID eventId,
        String tenantId,
        String candidateId,
        MigrationStatus status,
        int attempts,
        String lastError
) {
}
