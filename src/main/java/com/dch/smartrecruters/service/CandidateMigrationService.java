package com.dch.smartrecruters.service;

import com.dch.smartrecruters.client.SapClient;
import com.dch.smartrecruters.client.SmartRecruitersClient;
import com.dch.smartrecruters.client.sap.SapCandidate;
import com.dch.smartrecruters.client.smartrecruiters.SmartRecruitersCandidateRequest;
import com.dch.smartrecruters.domain.Candidate;
import com.dch.smartrecruters.mapper.CandidateMapper;
import com.dch.smartrecruters.state.MigrationRecordRepository;
import com.dch.smartrecruters.validation.CandidateValidator;

public class CandidateMigrationService {

    private final SapClient sapClient;
    private final CandidateMapper mapper;
    private final CandidateValidator validator;
    private final SmartRecruitersClient smartRecruitersClient;
    private final MigrationRecordRepository migrationRecordRepository;

    public CandidateMigrationService(
            SapClient sapClient,
            CandidateMapper mapper,
            CandidateValidator validator,
            SmartRecruitersClient smartRecruitersClient, MigrationRecordRepository migrationRecordRepository
    ) {
        this.sapClient = sapClient;
        this.mapper = mapper;
        this.validator = validator;
        this.smartRecruitersClient = smartRecruitersClient;
        this.migrationRecordRepository = migrationRecordRepository;
    }

    public void migrateCandidate(String tenantId, String candidateId) {
        if (!migrationRecordRepository.tryStart(tenantId, candidateId)) {
            return;
        }

        try {
            SapCandidate source = sapClient.getCandidate(tenantId, candidateId);

            Candidate candidate = mapper.map(source);
            validator.validate(candidate);

            SmartRecruitersCandidateRequest request = mapper.mapToRequest(candidate);

            smartRecruitersClient.createCandidate(tenantId, request);

            migrationRecordRepository.markCompleted(tenantId, candidateId);
        } catch (RuntimeException e) {
            migrationRecordRepository.markFailed(tenantId, candidateId);
            throw e;
        }
    }
}