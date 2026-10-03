package com.dch.smartrecruters.validation;

import com.dch.smartrecruters.domain.Candidate;

public class CandidateValidator {

    public void validate(Candidate candidate) {
        if (candidate.email() == null || candidate.email().isBlank()) {
            throw new IllegalArgumentException("Candidate email is required");
        }
    }
}