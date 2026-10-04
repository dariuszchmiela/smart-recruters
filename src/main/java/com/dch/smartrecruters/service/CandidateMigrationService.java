package com.dch.smartrecruters.service;

import com.dch.smartrecruters.client.SapClient;
import com.dch.smartrecruters.client.SmartRecruitersClient;
import com.dch.smartrecruters.client.sap.SapCandidate;
import com.dch.smartrecruters.client.smartrecruiters.SmartRecruitersCandidateRequest;
import com.dch.smartrecruters.domain.Candidate;
import com.dch.smartrecruters.mapper.CandidateMapper;
import com.dch.smartrecruters.state.MigrationRecordRepository;
import com.dch.smartrecruters.state.MigrationStatus;
import com.dch.smartrecruters.validation.CandidateValidator;

import java.util.function.Supplier;

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

    /**
     * Fetches the candidate from SAP and migrates it.
     */
    public CandidateMigrationOutcome migrateCandidate(String tenantId, String candidateId) {
        return migrate(tenantId, candidateId, () -> sapClient.getCandidate(tenantId, candidateId));
    }

    /**
     * Migrates a candidate that was already loaded from SAP (e.g. as part of a page),
     * so it is not fetched a second time. Claim and state transitions are the same.
     */
    public CandidateMigrationOutcome migrateCandidate(String tenantId, SapCandidate source) {
        return migrate(tenantId, source.id(), () -> source);
    }

    private CandidateMigrationOutcome migrate(
            String tenantId,
            String candidateId,
            Supplier<SapCandidate> sourceLoader
    ) {
        if (!migrationRecordRepository.tryStart(tenantId, candidateId)) {
            return notClaimed(tenantId, candidateId);
        }

        try {
            SapCandidate source = sourceLoader.get();

            Candidate candidate = mapper.map(source);
            validator.validate(candidate);

            SmartRecruitersCandidateRequest request = mapper.mapToRequest(candidate);

            smartRecruitersClient.createCandidate(tenantId, request);

            migrationRecordRepository.markCompleted(tenantId, candidateId);
            return CandidateMigrationOutcome.MIGRATED;
        } catch (RuntimeException e) {
            migrationRecordRepository.markFailed(tenantId, candidateId);
            throw e;
        }
    }

    /**
     * The claim only says "not mine"; the current status tells whether the record is already done.
     * Read after the failed claim, so a record finished by another worker in between counts as done.
     */
    private CandidateMigrationOutcome notClaimed(String tenantId, String candidateId) {
        return migrationRecordRepository.findStatus(tenantId, candidateId)
                .filter(status -> status == MigrationStatus.COMPLETED)
                .map(status -> CandidateMigrationOutcome.ALREADY_MIGRATED)
                .orElse(CandidateMigrationOutcome.CLAIMED_BY_OTHER_WORKER);
    }
}
