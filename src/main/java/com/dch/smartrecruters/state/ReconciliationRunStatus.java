package com.dch.smartrecruters.state;

public enum ReconciliationRunStatus {
    /**
     * Created, not picked up by a worker yet.
     */
    PENDING,
    /**
     * Scanning; updated_at is the heartbeat of the worker holding the run.
     */
    RUNNING,
    /**
     * Both systems scanned and every item classified; counters are final.
     */
    COMPLETED,
    /**
     * Stopped (scan error or abandoned worker). Not resumed - a new run starts from scratch.
     */
    FAILED
}
