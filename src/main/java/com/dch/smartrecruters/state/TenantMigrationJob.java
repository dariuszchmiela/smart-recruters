package com.dch.smartrecruters.state;

import java.time.Instant;
import java.util.UUID;

public record TenantMigrationJob(
        UUID jobId,
        String tenantId,
        TenantMigrationJobStatus status,
        int pageSize,
        int nextPage,
        long processedCount,
        long succeededCount,
        long skippedCount,
        long failedCount,
        String lastError,
        Instant createdAt,
        Instant updatedAt
) {
}
