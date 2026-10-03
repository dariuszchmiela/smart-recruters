package com.dch.smartrecruters.mapper;

import com.dch.smartrecruters.client.sap.SapCandidate;
import com.dch.smartrecruters.domain.Candidate;

public class CandidateMapper {

    public Candidate map(SapCandidate source) {
        return new Candidate(
                source.id(),
                source.tenantId(),
                source.firstName(),
                source.lastName(),
                source.email()
        );
    }
}