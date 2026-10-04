package com.dch.smartrecruters.state;

public enum ReconciliationResult {
    /**
     * In source and target, and the source-derived expected candidate and the target candidate have equal
     * fingerprints, which reconciliation treats as equivalence under {@code CandidateFingerprint}
     * canonicalization (SHA-256 collisions considered negligible; not necessarily character-for-character equal).
     * Says nothing about whether the migration itself would accept the source candidate.
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
     * In source and target, but the compared fields differ even after {@code CandidateFingerprint}
     * canonicalization.
     */
    MISMATCHED
}
