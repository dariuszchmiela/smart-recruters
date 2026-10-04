package com.dch.smartrecruters.api;

import com.dch.smartrecruters.state.TenantMigrationJob;
import com.dch.smartrecruters.state.TenantMigrationJobStatus;

import java.time.Instant;
import java.util.UUID;

public record TenantMigrationJobResponse(
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

    static TenantMigrationJobResponse from(TenantMigrationJob job) {
        return new TenantMigrationJobResponse(
                job.jobId(),
                job.tenantId(),
                job.status(),
                job.pageSize(),
                job.nextPage(),
                job.processedCount(),
                job.succeededCount(),
                job.skippedCount(),
                job.failedCount(),
                job.lastError(),
                job.createdAt(),
                job.updatedAt()
        );
    }
}
