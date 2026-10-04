package com.dch.smartrecruters.service;

import com.dch.smartrecruters.client.SapClient;
import com.dch.smartrecruters.client.SmartRecruitersClient;
import com.dch.smartrecruters.client.sap.SapCandidate;
import com.dch.smartrecruters.client.smartrecruiters.SmartRecruitersCandidateRequest;
import com.dch.smartrecruters.mapper.CandidateMapper;
import com.dch.smartrecruters.state.MigrationRecordRepository;
import com.dch.smartrecruters.state.MigrationStatus;
import com.dch.smartrecruters.validation.CandidateValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

class CandidateMigrationServiceTest {

    private SapClient sapClient;
    private SmartRecruitersClient smartRecruitersClient;
    private MigrationRecordRepository migrationRecordRepository;

    private CandidateMigrationService service;

    @BeforeEach
    void setUp() {
        sapClient = mock(SapClient.class);
        smartRecruitersClient = mock(SmartRecruitersClient.class);
        migrationRecordRepository = mock(MigrationRecordRepository.class);

        service = new CandidateMigrationService(
                sapClient,
                new CandidateMapper(),
                new CandidateValidator(),
                smartRecruitersClient,
                migrationRecordRepository
        );
    }

    @Test
    void shouldMigrateCandidateFromSapToSmartRecruiters() {
        SapCandidate source = candidate();

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

    @Test
    void shouldSkipCandidateWhenMigrationWasAlreadyStarted() {
        when(migrationRecordRepository.tryStart("tenant-1", "candidate-1"))
                .thenReturn(false);

        service.migrateCandidate("tenant-1", "candidate-1");

        verifyNoInteractions(sapClient);
        verifyNoInteractions(smartRecruitersClient);
    }

    @Test
    void shouldMarkMigrationAsFailedWhenTargetCallFails() {
        when(migrationRecordRepository.tryStart("tenant-1", "candidate-1"))
                .thenReturn(true);

        when(sapClient.getCandidate("tenant-1", "candidate-1"))
                .thenReturn(candidate());

        doThrow(new RuntimeException("Target unavailable"))
                .when(smartRecruitersClient)
                .createCandidate(anyString(), any());

        assertThrows(
                RuntimeException.class,
                () -> service.migrateCandidate("tenant-1", "candidate-1")
        );

        verify(migrationRecordRepository)
                .markFailed("tenant-1", "candidate-1");
    }

    @Test
    void shouldMigrateAlreadyLoadedCandidateWithoutFetchingItAgain() {
        when(migrationRecordRepository.tryStart("tenant-1", "candidate-1"))
                .thenReturn(true);

        CandidateMigrationOutcome outcome = service.migrateCandidate("tenant-1", candidate());

        assertEquals(CandidateMigrationOutcome.MIGRATED, outcome);
        verifyNoInteractions(sapClient);
        verify(smartRecruitersClient).createCandidate(
                "tenant-1",
                new SmartRecruitersCandidateRequest("candidate-1", "John", "Smith", "john@example.com")
        );
        verify(migrationRecordRepository).markCompleted("tenant-1", "candidate-1");
    }

    @Test
    void shouldSkipAlreadyLoadedCandidateWhenItCannotBeClaimed() {
        when(migrationRecordRepository.tryStart("tenant-1", "candidate-1"))
                .thenReturn(false);
        when(migrationRecordRepository.findStatus("tenant-1", "candidate-1"))
                .thenReturn(Optional.of(MigrationStatus.IN_PROGRESS));

        CandidateMigrationOutcome outcome = service.migrateCandidate("tenant-1", candidate());

        assertEquals(CandidateMigrationOutcome.CLAIMED_BY_OTHER_WORKER, outcome);
        verifyNoInteractions(smartRecruitersClient);
        verify(migrationRecordRepository, never()).markCompleted(anyString(), anyString());
    }

    @Test
    void shouldReportAlreadyMigratedCandidateWithoutSendingItAgain() {
        when(migrationRecordRepository.tryStart("tenant-1", "candidate-1"))
                .thenReturn(false);
        when(migrationRecordRepository.findStatus("tenant-1", "candidate-1"))
                .thenReturn(Optional.of(MigrationStatus.COMPLETED));

        CandidateMigrationOutcome outcome = service.migrateCandidate("tenant-1", candidate());

        assertEquals(CandidateMigrationOutcome.ALREADY_MIGRATED, outcome);
        verifyNoInteractions(smartRecruitersClient);
        verify(migrationRecordRepository, never()).markCompleted(anyString(), anyString());
        verify(migrationRecordRepository, never()).markFailed(anyString(), anyString());
    }

    @Test
    void shouldMarkAlreadyLoadedInvalidCandidateAsFailed() {
        when(migrationRecordRepository.tryStart("tenant-1", "candidate-1"))
                .thenReturn(true);
        SapCandidate withoutEmail = new SapCandidate("candidate-1", "tenant-1", "John", "Smith", " ");

        assertThrows(
                IllegalArgumentException.class,
                () -> service.migrateCandidate("tenant-1", withoutEmail)
        );

        verifyNoInteractions(smartRecruitersClient);
        verify(migrationRecordRepository).markFailed("tenant-1", "candidate-1");
    }

    private SapCandidate candidate() {
        return new SapCandidate(
                "candidate-1",
                "tenant-1",
                "John",
                "Smith",
                "john@example.com"
        );
    }
}