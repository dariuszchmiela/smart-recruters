package com.dch.smartrecruters.service;

import com.dch.smartrecruters.client.SapClient;
import com.dch.smartrecruters.client.SmartRecruitersClient;
import com.dch.smartrecruters.client.sap.SapCandidate;
import com.dch.smartrecruters.client.smartrecruiters.SmartRecruitersCandidateRequest;
import com.dch.smartrecruters.mapper.CandidateMapper;
import com.dch.smartrecruters.state.MigrationRecordRepository;
import com.dch.smartrecruters.validation.CandidateValidator;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.*;

class CandidateMigrationServiceTest {

    @Test
    void shouldMigrateCandidateFromSapToSmartRecruiters() {
        SapClient sapClient = mock(SapClient.class);
        SmartRecruitersClient smartRecruitersClient = mock(SmartRecruitersClient.class);
        MigrationRecordRepository migrationRecordRepository =
                mock(MigrationRecordRepository.class);

        CandidateMigrationService service = new CandidateMigrationService(
                sapClient,
                new CandidateMapper(),
                new CandidateValidator(),
                smartRecruitersClient,
                migrationRecordRepository
        );

        SapCandidate source = new SapCandidate(
                "candidate-1",
                "tenant-1",
                "John",
                "Smith",
                "john@example.com"
        );

        when(migrationRecordRepository.tryStart("tenant-1", "candidate-1"))
                .thenReturn(true);

        when(sapClient.getCandidate("tenant-1", "candidate-1"))
                .thenReturn(source);

        service.migrateCandidate("tenant-1", "candidate-1");

        verify(smartRecruitersClient).createCandidate(
                "tenant-1",
                new SmartRecruitersCandidateRequest(
                        "candidate-1",
                        "John",
                        "Smith",
                        "john@example.com"
                )
        );

        verify(migrationRecordRepository)
                .markCompleted("tenant-1", "candidate-1");
    }
}