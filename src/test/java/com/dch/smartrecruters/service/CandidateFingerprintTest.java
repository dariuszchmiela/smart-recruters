package com.dch.smartrecruters.service;

import com.dch.smartrecruters.client.smartrecruiters.SmartRecruitersCandidate;
import com.dch.smartrecruters.client.smartrecruiters.SmartRecruitersCandidateRequest;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CandidateFingerprintTest {

    @Test
    void shouldBeDeterministicLowercaseHexSha256OfCanonicalForm() throws Exception {
        String canonical = CandidateFingerprint.canonical("candidate-1", "John", "Smith", "john@example.com");

        assertEquals("v1;+11:candidate-1;+4:John;+5:Smith;+16:john@example.com;", canonical);
        String expected = HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8))
        );
        assertEquals(expected, CandidateFingerprint.of("candidate-1", "John", "Smith", "john@example.com"));
        assertTrue(expected.matches("[0-9a-f]{64}"));
    }

    @Test
    void shouldGiveSameValueForSourceAndTargetWithEquivalentCanonicalData() {
        String source = CandidateFingerprint.of(
                new SmartRecruitersCandidateRequest("candidate-1", " John ", "Smith\t", "john@example.com"));
        String target = CandidateFingerprint.of(
                new SmartRecruitersCandidate("candidate-1", "John", "Smith", " john@example.com"));

        assertEquals(source, target);
        // repeated computation, same value
        assertEquals(source, CandidateFingerprint.of(
                new SmartRecruitersCandidate("candidate-1", "John", "Smith", "john@example.com")));
    }

    @Test
    void shouldTreatNullAndBlankAsTheSameAbsentValue() {
        assertEquals(
                CandidateFingerprint.of("candidate-1", "John", "Smith", null),
                CandidateFingerprint.of("candidate-1", "John", "Smith", "   ")
        );
        assertEquals("v1;+11:candidate-1;+4:John;+5:Smith;-;",
                CandidateFingerprint.canonical("candidate-1", "John", "Smith", ""));
    }

    @Test
    void shouldNormalizeUnicodeCompositionButNotCase() {
        String composed = "Zoë";       // ë as one code point
        String decomposed = "Zoë";    // e + combining diaeresis

        assertEquals(
                CandidateFingerprint.of("candidate-1", composed, "Smith", "zoe@example.com"),
                CandidateFingerprint.of("candidate-1", decomposed, "Smith", "zoe@example.com")
        );
        assertNotEquals(
                CandidateFingerprint.of("candidate-1", "John", "Smith", "John@example.com"),
                CandidateFingerprint.of("candidate-1", "John", "Smith", "john@example.com")
        );
    }

    @Test
    void shouldChangeWhenAnyOwnedFieldChanges() {
        String base = CandidateFingerprint.of("candidate-1", "John", "Smith", "john@example.com");

        assertNotEquals(base, CandidateFingerprint.of("candidate-2", "John", "Smith", "john@example.com"));
        assertNotEquals(base, CandidateFingerprint.of("candidate-1", "Jon", "Smith", "john@example.com"));
        assertNotEquals(base, CandidateFingerprint.of("candidate-1", "John", "Smyth", "john@example.com"));
        assertNotEquals(base, CandidateFingerprint.of("candidate-1", "John", "Smith", "jsmith@example.com"));
    }

    @Test
    void shouldNotBeFooledByShiftedFieldBoundaries() {
        assertNotEquals(
                CandidateFingerprint.of("candidate-1", "Ann", "aSmith", "a@example.com"),
                CandidateFingerprint.of("candidate-1", "Anna", "Smith", "a@example.com")
        );
        // a value that looks like an encoded field cannot collide with a real absent field
        assertNotEquals(
                CandidateFingerprint.of("candidate-1", "-", "Smith", "a@example.com"),
                CandidateFingerprint.of("candidate-1", null, "Smith", "a@example.com")
        );
        assertNotEquals(
                CandidateFingerprint.of("candidate-1", "John;+5:Smith", null, "a@example.com"),
                CandidateFingerprint.of("candidate-1", "John", "Smith", "a@example.com")
        );
    }
}
