package com.dch.smartrecruters.validation;

import com.dch.smartrecruters.domain.Candidate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CandidateValidatorTest {

    private final CandidateValidator validator = new CandidateValidator();

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "   ", "\t"})
    void shouldRejectMissingOrBlankEmailWithDedicatedException(String email) {
        Candidate candidate = new Candidate("candidate-1", "tenant-1", "John", "Smith", email);

        CandidateValidationException exception =
                assertThrows(CandidateValidationException.class, () -> validator.validate(candidate));

        assertEquals("Candidate email is required", exception.getMessage());
    }

    @Test
    void shouldAcceptCandidateWithEmail() {
        assertDoesNotThrow(() -> validator.validate(
                new Candidate("candidate-1", "tenant-1", "John", "Smith", "john@example.com")));
    }

    @Test
    void shouldNotBeAGenericIllegalArgumentException() {
        // the delta path treats only this type as a permanent data problem
        assertFalse(IllegalArgumentException.class.isAssignableFrom(CandidateValidationException.class));
    }
}
