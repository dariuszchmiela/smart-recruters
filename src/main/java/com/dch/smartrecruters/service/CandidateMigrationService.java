package com.dch.smartrecruters.service;

import com.dch.smartrecruters.client.SapClient;
import com.dch.smartrecruters.client.SmartRecruitersClient;
import com.dch.smartrecruters.client.sap.SapCandidate;
import com.dch.smartrecruters.domain.Candidate;
import com.dch.smartrecruters.mapper.CandidateMapper;
import com.dch.smartrecruters.validation.CandidateValidator;

public class CandidateMigrationService {

    private final SapClient sapClient;
    private final CandidateMapper mapper;
    private final CandidateValidator validator;
    private final SmartRecruitersClient smartRecruitersClient;

    public CandidateMigrationService(
            SapClient sapClient,
            CandidateMapper mapper,
            CandidateValidator validator,
            SmartRecruitersClient smartRecruitersClient
    ) {
        this.sapClient = sapClient;
        this.mapper = mapper;
        this.validator = validator;
        this.smartRecruitersClient = smartRecruitersClient;
    }

    public void migrateCandidate(String tenantId, String candidateId) {
        SapCandidate source = sapClient.getCandidate(tenantId, candidateId);
        Candidate candidate = mapper.map(source);
        validator.validate(candidate);
    }
}