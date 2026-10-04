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
import org.mockito.ArgumentCaptor;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
        // this worker still owns the record unless a test says otherwise
        when(migrationRecordRepository.markCompleted(anyString(), anyString(), any())).thenReturn(true);
        when(migrationRecordRepository.markFailed(anyString(), anyString(), any())).thenReturn(true);

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

        when(migrationRecordRepository.tryStart(eq("tenant-1"), eq("candidate-1"), any()))
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
                .markCompleted(eq("tenant-1"), eq("candidate-1"), any());
    }

    @Test
    void shouldSkipCandidateWhenMigrationWasAlreadyStarted() {
        when(migrationRecordRepository.tryStart(eq("tenant-1"), eq("candidate-1"), any()))
                .thenReturn(false);

        service.migrateCandidate("tenant-1", "candidate-1");

        verifyNoInteractions(sapClient);
        verifyNoInteractions(smartRecruitersClient);
    }

    @Test
    void shouldMarkMigrationAsFailedWhenTargetCallFails() {
        when(migrationRecordRepository.tryStart(eq("tenant-1"), eq("candidate-1"), any()))
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
                .markFailed(eq("tenant-1"), eq("candidate-1"), any());
    }

    @Test
    void shouldMigrateAlreadyLoadedCandidateWithoutFetchingItAgain() {
        when(migrationRecordRepository.tryStart(eq("tenant-1"), eq("candidate-1"), any()))
                .thenReturn(true);

        CandidateMigrationOutcome outcome = service.migrateCandidate("tenant-1", candidate());

        assertEquals(CandidateMigrationOutcome.MIGRATED, outcome);
        verifyNoInteractions(sapClient);
        verify(smartRecruitersClient).createCandidate(
                "tenant-1",
                new SmartRecruitersCandidateRequest("candidate-1", "John", "Smith", "john@example.com")
        );
        verify(migrationRecordRepository).markCompleted(eq("tenant-1"), eq("candidate-1"), any());
    }

    @Test
    void shouldSkipAlreadyLoadedCandidateWhenItCannotBeClaimed() {
        when(migrationRecordRepository.tryStart(eq("tenant-1"), eq("candidate-1"), any()))
                .thenReturn(false);
        when(migrationRecordRepository.findStatus("tenant-1", "candidate-1"))
                .thenReturn(Optional.of(MigrationStatus.IN_PROGRESS));

        CandidateMigrationOutcome outcome = service.migrateCandidate("tenant-1", candidate());

        assertEquals(CandidateMigrationOutcome.CLAIMED_BY_OTHER_WORKER, outcome);
        verifyNoInteractions(smartRecruitersClient);
        verify(migrationRecordRepository, never()).markCompleted(anyString(), anyString(), any());
    }

    @Test
    void shouldReportAlreadyMigratedCandidateWithoutSendingItAgain() {
        when(migrationRecordRepository.tryStart(eq("tenant-1"), eq("candidate-1"), any()))
                .thenReturn(false);
        when(migrationRecordRepository.findStatus("tenant-1", "candidate-1"))
                .thenReturn(Optional.of(MigrationStatus.COMPLETED));

        CandidateMigrationOutcome outcome = service.migrateCandidate("tenant-1", candidate());

        assertEquals(CandidateMigrationOutcome.ALREADY_MIGRATED, outcome);
        verifyNoInteractions(smartRecruitersClient);
        verify(migrationRecordRepository, never()).markCompleted(anyString(), anyString(), any());
        verify(migrationRecordRepository, never()).markFailed(anyString(), anyString(), any());
    }

    @Test
    void shouldMarkAlreadyLoadedInvalidCandidateAsFailed() {
        when(migrationRecordRepository.tryStart(eq("tenant-1"), eq("candidate-1"), any()))
                .thenReturn(true);
        SapCandidate withoutEmail = new SapCandidate("candidate-1", "tenant-1", "John", "Smith", " ");

        assertThrows(
                IllegalArgumentException.class,
                () -> service.migrateCandidate("tenant-1", withoutEmail)
        );

        verifyNoInteractions(smartRecruitersClient);
        verify(migrationRecordRepository).markFailed(eq("tenant-1"), eq("candidate-1"), any());
    }

    @Test
    void shouldUseOneLeaseOwnerForClaimAndCompletion() {
        when(migrationRecordRepository.tryStart(eq("tenant-1"), eq("candidate-1"), any())).thenReturn(true);

        assertEquals(CandidateMigrationOutcome.MIGRATED, service.migrateCandidate("tenant-1", candidate()));

        ArgumentCaptor<UUID> claimedBy = ArgumentCaptor.forClass(UUID.class);
        verify(migrationRecordRepository).tryStart(eq("tenant-1"), eq("candidate-1"), claimedBy.capture());
        verify(migrationRecordRepository).markCompleted("tenant-1", "candidate-1", claimedBy.getValue());
    }

    @Test
    void shouldUseNewLeaseOwnerForEveryAttempt() {
        when(migrationRecordRepository.tryStart(eq("tenant-1"), eq("candidate-1"), any())).thenReturn(true);

        service.migrateCandidate("tenant-1", candidate());
        service.migrateCandidate("tenant-1", candidate());

        ArgumentCaptor<UUID> claimedBy = ArgumentCaptor.forClass(UUID.class);
        verify(migrationRecordRepository, times(2)).tryStart(eq("tenant-1"), eq("candidate-1"), claimedBy.capture());
        assertNotEquals(claimedBy.getAllValues().get(0), claimedBy.getAllValues().get(1));
    }

    @Test
    void shouldNotReportMigratedWhenClaimWasTakenOverWhileTargetCallRan() {
        FencedRecords records = new FencedRecords();
        CandidateMigrationService fencedService = serviceWith(records);
        UUID[] workerB = new UUID[1];
        // worker A stalls in the target call beyond the claim timeout; worker B takes the record over.
        // A's target call still finishes successfully afterwards.
        doAnswer(invocation -> {
            workerB[0] = records.takeOver("tenant-1", "candidate-1");
            return null;
        }).when(smartRecruitersClient).createCandidate(anyString(), any());

        CandidateMigrationOutcome outcome = fencedService.migrateCandidate("tenant-1", candidate());

        assertEquals(CandidateMigrationOutcome.LEASE_LOST, outcome);
        verify(smartRecruitersClient).createCandidate(eq("tenant-1"), any());
        // A did not finalize anything: the record is still B's
        assertEquals(MigrationStatus.IN_PROGRESS, records.status("tenant-1", "candidate-1"));
        assertEquals(workerB[0], records.owner("tenant-1", "candidate-1"));
        assertTrue(records.markCompleted("tenant-1", "candidate-1", workerB[0]));
    }

    @Test
    void shouldNotRecordFailureOfStaleAttemptAfterTakeOver() {
        FencedRecords records = new FencedRecords();
        CandidateMigrationService fencedService = serviceWith(records);
        doAnswer(invocation -> {
            records.takeOver("tenant-1", "candidate-1");
            throw new RuntimeException("Target unavailable");
        }).when(smartRecruitersClient).createCandidate(anyString(), any());

        // A's failure belongs to an attempt that no longer owns the record: not thrown, not recorded
        CandidateMigrationOutcome outcome = fencedService.migrateCandidate("tenant-1", candidate());

        assertEquals(CandidateMigrationOutcome.LEASE_LOST, outcome);
        assertEquals(MigrationStatus.IN_PROGRESS, records.status("tenant-1", "candidate-1"));
    }

    private CandidateMigrationService serviceWith(MigrationRecordRepository records) {
        return new CandidateMigrationService(
                sapClient, new CandidateMapper(), new CandidateValidator(), smartRecruitersClient, records
        );
    }

    /**
     * Claim and fencing rules of the JDBC repository; a take-over simulates an expired claim being reclaimed.
     */
    private static final class FencedRecords implements MigrationRecordRepository {

        private final Map<String, MigrationStatus> statuses = new HashMap<>();
        private final Map<String, UUID> owners = new HashMap<>();

        @Override
        public synchronized boolean tryStart(String tenantId, String sourceRecordId, UUID leaseOwner) {
            String key = tenantId + ":" + sourceRecordId;
            MigrationStatus current = statuses.get(key);
            if (current != null && current != MigrationStatus.FAILED) {
                return false;
            }
            statuses.put(key, MigrationStatus.IN_PROGRESS);
            owners.put(key, leaseOwner);
            return true;
        }

        @Override
        public boolean markCompleted(String tenantId, String sourceRecordId, UUID leaseOwner) {
            return finish(tenantId + ":" + sourceRecordId, leaseOwner, MigrationStatus.COMPLETED);
        }

        @Override
        public boolean markFailed(String tenantId, String sourceRecordId, UUID leaseOwner) {
            return finish(tenantId + ":" + sourceRecordId, leaseOwner, MigrationStatus.FAILED);
        }

        @Override
        public synchronized Optional<MigrationStatus> findStatus(String tenantId, String sourceRecordId) {
            return Optional.ofNullable(statuses.get(tenantId + ":" + sourceRecordId));
        }

        synchronized UUID takeOver(String tenantId, String sourceRecordId) {
            UUID newOwner = UUID.randomUUID();
            owners.put(tenantId + ":" + sourceRecordId, newOwner);
            return newOwner;
        }

        synchronized MigrationStatus status(String tenantId, String sourceRecordId) {
            return statuses.get(tenantId + ":" + sourceRecordId);
        }

        synchronized UUID owner(String tenantId, String sourceRecordId) {
            return owners.get(tenantId + ":" + sourceRecordId);
        }

        private synchronized boolean finish(String key, UUID leaseOwner, MigrationStatus status) {
            if (statuses.get(key) != MigrationStatus.IN_PROGRESS || !leaseOwner.equals(owners.get(key))) {
                return false;
            }
            statuses.put(key, status);
            owners.remove(key);
            return true;
        }
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