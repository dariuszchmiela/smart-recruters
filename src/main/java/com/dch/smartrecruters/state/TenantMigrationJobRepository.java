package com.dch.smartrecruters.state;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Every state-changing method that takes a {@code leaseOwner} only succeeds while that owner
 * still holds the job in RUNNING; {@code false} means the lease was lost and the caller must stop.
 */
public interface TenantMigrationJobRepository {

    /**
     * Creates a PENDING job, or returns the tenant's existing unfinished job (PENDING, RUNNING or FAILED).
     */
    TenantMigrationJob createOrGetUnfinished(String tenantId, int pageSize);

    Optional<TenantMigrationJob> findById(UUID jobId);

    /**
     * PENDING, FAILED or RUNNING with an expired lease -> RUNNING owned by {@code leaseOwner}.
     */
    Optional<TenantMigrationJob> tryClaim(UUID jobId, UUID leaseOwner);

    /**
     * Adds the page counters and moves the checkpoint in one statement; also renews the lease.
     */
    boolean recordPage(UUID jobId, UUID leaseOwner, int nextPage, JobProgress progress);

    /**
     * RUNNING -> COMPLETED, or COMPLETED_WITH_ERRORS when any candidate failed.
     */
    boolean complete(UUID jobId, UUID leaseOwner);

    boolean markFailed(UUID jobId, UUID leaseOwner, String error);

    /**
     * PENDING jobs and RUNNING jobs whose lease expired (owner most likely died).
     */
    List<UUID> findResumable(int limit);
}
