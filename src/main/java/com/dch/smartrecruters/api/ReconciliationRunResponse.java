package com.dch.smartrecruters.api;

import com.dch.smartrecruters.state.ReconciliationRun;
import com.dch.smartrecruters.state.ReconciliationRunStatus;

import java.time.Instant;
import java.util.UUID;

public record ReconciliationRunResponse(
        UUID runId,
        String tenantId,
        ReconciliationRunStatus status,
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

    static ReconciliationRunResponse from(ReconciliationRun run) {
        return new ReconciliationRunResponse(
                run.runId(),
                run.tenantId(),
                run.status(),
                run.sourceCount(),
                run.targetCount(),
                run.matchedCount(),
                run.missingInTargetCount(),
                run.unexpectedInTargetCount(),
                run.mismatchedCount(),
                run.lastError(),
                run.createdAt(),
                run.updatedAt(),
                run.completedAt()
        );
    }
}
