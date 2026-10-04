package com.dch.smartrecruters.state;

/**
 * One scanned candidate as written to the reconciliation working table.
 */
public record FingerprintedCandidate(
        String externalId,
        String fingerprint
) {
}
