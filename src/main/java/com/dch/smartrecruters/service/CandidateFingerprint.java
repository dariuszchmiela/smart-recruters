package com.dch.smartrecruters.service;

import com.dch.smartrecruters.client.smartrecruiters.SmartRecruitersCandidate;
import com.dch.smartrecruters.client.smartrecruiters.SmartRecruitersCandidateRequest;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.HexFormat;

/**
 * Deterministic, persistable fingerprint of the candidate fields owned by this migration
 * (externalId, firstName, lastName, email). Source and target are fingerprinted with exactly the
 * same rules, so equal fingerprints mean "the target holds what the migration should have written".
 * <p>
 * Canonical representation (version 1), fields in fixed order:
 * <pre>
 * v1;&lt;field&gt;;&lt;field&gt;;&lt;field&gt;;&lt;field&gt;;
 * field = "-"                      when absent
 *       | "+" length ":" value     otherwise (length in UTF-16 chars of the normalized value)
 * </pre>
 * Normalization of every field: Unicode NFC, then trim; null and blank are both "absent".
 * No case folding: names and emails are compared as migrated (the migration copies them verbatim).
 * The length prefix makes the encoding unambiguous - ("ab", "c") and ("a", "bc") differ.
 * Fingerprint = lowercase hex SHA-256 of the UTF-8 bytes of the canonical representation.
 */
public final class CandidateFingerprint {

    static final String VERSION = "v1";

    private CandidateFingerprint() {
    }

    /**
     * What the migration is expected to write to the target for a source candidate.
     */
    public static String of(SmartRecruitersCandidateRequest expected) {
        return of(expected.externalId(), expected.firstName(), expected.lastName(), expected.email());
    }

    /**
     * What the target actually holds.
     */
    public static String of(SmartRecruitersCandidate actual) {
        return of(actual.externalId(), actual.firstName(), actual.lastName(), actual.email());
    }

    static String of(String externalId, String firstName, String lastName, String email) {
        return sha256(canonical(externalId, firstName, lastName, email));
    }

    static String canonical(String externalId, String firstName, String lastName, String email) {
        StringBuilder canonical = new StringBuilder(VERSION).append(';');
        for (String field : new String[]{externalId, firstName, lastName, email}) {
            String value = normalize(field);
            if (value == null) {
                canonical.append('-');
            } else {
                canonical.append('+').append(value.length()).append(':').append(value);
            }
            canonical.append(';');
        }
        return canonical.toString();
    }

    static String normalize(String value) {
        if (value == null) {
            return null;
        }
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFC).strip();
        return normalized.isEmpty() ? null : normalized;
    }

    private static String sha256(String canonical) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
