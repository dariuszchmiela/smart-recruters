package com.dch.smartrecruters.service;

public enum DeltaEventOutcome {
    /**
     * The latest source state was written to the target.
     */
    PROCESSED,
    /**
     * The event was already COMPLETED earlier (duplicate delivery); nothing was done.
     */
    DUPLICATE
}
