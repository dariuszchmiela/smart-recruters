package com.dch.smartrecruters.state;

import java.time.Instant;
import java.util.UUID;

public record ReconciliationRun(
        UUID runId,
        String tenantId,
        ReconciliationRunStatus status,
        int pageSize,
        long sourceCount,
        long targetCount,
        long matchedCount,
        long missingInTargetCount,
        long unexpectedInTargetCount,
        long mismatchedCount,
        String lastError,
        Instant createdAt,
        Instant updatedAt,
        Instant completedAt
) {
}
