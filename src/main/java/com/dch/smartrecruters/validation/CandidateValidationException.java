package com.dch.smartrecruters.validation;

/**
 * Candidate data violates a migration rule. The message is a controlled application text
 * (no candidate values), safe to log and to persist.
 * <p>
 * Distinct from {@link IllegalArgumentException} on purpose: only this type means
 * "the source data itself is invalid", which is permanent for the given source state.
 */
public class CandidateValidationException extends RuntimeException {

    public CandidateValidationException(String message) {
        super(message);
    }
}
