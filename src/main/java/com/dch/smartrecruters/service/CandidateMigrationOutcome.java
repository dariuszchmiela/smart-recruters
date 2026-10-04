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
    CLAIMED_BY_OTHER_WORKER
}
