package com.dch.smartrecruters.state;

import java.util.Optional;
import java.util.UUID;

public interface MigrationRecordRepository {

    /**
     * Atomic claim owned by {@code leaseOwner} (one token per processing attempt):
     * new -> IN_PROGRESS, FAILED -> IN_PROGRESS, stale IN_PROGRESS -> IN_PROGRESS;
     * COMPLETED and fresh IN_PROGRESS are left untouched (returns false).
     */
    boolean tryStart(String tenantId, String sourceRecordId, UUID leaseOwner);

    /**
     * @return false when {@code leaseOwner} no longer holds the record (claim taken over by another worker)
     */
    boolean markCompleted(String tenantId, String sourceRecordId, UUID leaseOwner);

    /**
     * @return false when {@code leaseOwner} no longer holds the record (claim taken over by another worker)
     */
    boolean markFailed(String tenantId, String sourceRecordId, UUID leaseOwner);

    Optional<MigrationStatus> findStatus(String tenantId, String sourceRecordId);
}
