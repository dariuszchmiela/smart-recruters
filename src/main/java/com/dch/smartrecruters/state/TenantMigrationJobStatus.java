package com.dch.smartrecruters.state;

public enum TenantMigrationJobStatus {
    /**
     * Created, not picked up by a worker yet.
     */
    PENDING,
    /**
     * Held by a worker; updated_at acts as the lease heartbeat.
     */
    RUNNING,
    /**
     * All pages processed, every candidate migrated or skipped.
     */
    COMPLETED,
    /**
     * All pages processed, some candidates failed (their records stay FAILED and can be retried).
     */
    COMPLETED_WITH_ERRORS,
    /**
     * The job itself stopped (e.g. SAP page could not be read); resumable from its checkpoint.
     */
    FAILED
}
