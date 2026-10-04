package com.dch.smartrecruters.service;

import com.dch.smartrecruters.client.smartrecruiters.SmartRecruitersCandidate;
import com.dch.smartrecruters.client.smartrecruiters.SmartRecruitersCandidateRequest;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.HexFormat;

/**
 * Deterministic, persistable fingerprint of the candidate fields owned by this migration:
 * externalId, firstName, lastName, email. Nothing else is compared (no target-generated id,
 * no timestamps, no migration state).
 * <p>
 * Source and target are fingerprinted with the same canonicalization rules. Reconciliation treats equal
 * fingerprints as evidence that the two candidates are <b>equivalent under these rules</b> (SHA-256
 * collisions are theoretically possible but considered negligible for this use case) - not that the
 * original strings are equal character for character. For example, a source firstName {@code " John "}
 * and a target firstName {@code "John"} produce the same fingerprint.
 * <p>
 * Canonical representation (version 1), fields in fixed order:
 * <pre>
 * v1;&lt;field&gt;;&lt;field&gt;;&lt;field&gt;;&lt;field&gt;;
 * field = "-"                      when absent
 *       | "+" length ":" value     otherwise (length in UTF-16 chars of the normalized value)
 * </pre>
 * Canonicalization of every field, in this order:
 * <ol>
 *   <li>Unicode NFC normalization (composed and decomposed forms of the same text are equivalent)</li>
 *   <li>surrounding whitespace removed with {@link String#strip()} (Unicode-aware)</li>
 *   <li>null and blank (empty after stripping) are both treated as "absent"</li>
 * </ol>
 * There is no case folding and no other business normalization: {@code "John"} and {@code "john"},
 * or {@code "John@example.com"} and {@code "john@example.com"}, are different.
 * The length prefix makes the encoding unambiguous - ("ab", "c") and ("a", "bc") differ.
 * Fingerprint = lowercase hex SHA-256 of the UTF-8 bytes of the canonical representation.
 */
public final class CandidateFingerprint {

    static final String VERSION = "v1";

    private CandidateFingerprint() {
    }

    /**
     * Source side: a source candidate already mapped into the target-owned field shape.
     */
    public static String of(SmartRecruitersCandidateRequest expected) {
        return of(expected.externalId(), expected.firstName(), expected.lastName(), expected.email());
    }

    /**
     * Target side: the candidate as read from the target.
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
