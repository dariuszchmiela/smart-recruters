package com.dch.smartrecruters.state;

/**
 * Outcome for one externalId of a run. A fingerprint is null when the candidate was not seen on that side.
 */
public record ReconciliationItem(
        String externalId,
        ReconciliationResult result,
        String sourceFingerprint,
        String targetFingerprint
) {
}
