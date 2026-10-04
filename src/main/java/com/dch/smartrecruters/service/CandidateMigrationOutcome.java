package com.dch.smartrecruters.service;

public enum CandidateMigrationOutcome {
    /**
     * This call claimed the record and delivered it to the target.
     */
    MIGRATED,
    /**
     * Not claimed because the record is already COMPLETED (by an earlier run, or by a run
     * that crashed before saving its job checkpoint). The candidate is in the target.
     */
    ALREADY_MIGRATED,
    /**
     * Not claimed because another worker currently holds it (fresh IN_PROGRESS);
     * the result of this record is decided by that worker, not by this call.
     */
    CLAIMED_BY_OTHER_WORKER,
    /**
     * This call claimed the record, but its claim went stale and was taken over by another worker before
     * the record could be finished. The target call may have happened (it is idempotent), but this call
     * neither completed nor failed the record: its outcome is decided by the new owner, as for
     * {@link #CLAIMED_BY_OTHER_WORKER}. Never reported as {@link #MIGRATED}.
     */
    LEASE_LOST
}
