package com.dch.smartrecruters.state;

public enum ReconciliationResult {
    /**
     * In source and target with the same fingerprint.
     */
    MATCHED,
    /**
     * In source, not in target.
     */
    MISSING_IN_TARGET,
    /**
     * In target, not in source.
     */
    UNEXPECTED_IN_TARGET,
    /**
     * In source and target, but the migrated fields differ.
     */
    MISMATCHED
}
