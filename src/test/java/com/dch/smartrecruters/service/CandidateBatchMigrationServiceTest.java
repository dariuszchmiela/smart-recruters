package com.dch.smartrecruters.service;

import com.dch.smartrecruters.client.ExternalSystemException;
import com.dch.smartrecruters.client.FailureType;
import com.dch.smartrecruters.client.SapClient;
import com.dch.smartrecruters.client.SmartRecruitersClient;
import com.dch.smartrecruters.client.sap.SapCandidate;
import com.dch.smartrecruters.client.sap.SapCandidatePage;
import com.dch.smartrecruters.client.smartrecruiters.SmartRecruitersCandidatePage;
import com.dch.smartrecruters.client.smartrecruiters.SmartRecruitersCandidateRequest;
import com.dch.smartrecruters.mapper.CandidateMapper;
import com.dch.smartrecruters.state.MigrationRecordRepository;
import com.dch.smartrecruters.state.MigrationStatus;
import com.dch.smartrecruters.state.TenantMigrationJob;
import com.dch.smartrecruters.state.TenantMigrationJobStatus;
import com.dch.smartrecruters.validation.CandidateValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.ResourceAccessException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real {@link CandidateMigrationService} (claim, mapping, validation, state transitions)
 * with in-memory SAP, target and state.
 */
class CandidateBatchMigrationServiceTest {

    private static final String TENANT = "tenant-1";
    private static final int PAGE_SIZE = 3;
    private static final int PARALLELISM = 4;

    private FakeSapClient sap;
    private RecordingSmartRecruitersClient target;
    private InMemoryMigrationRecordRepository records;
    private InMemoryTenantMigrationJobRepository jobs;
    private CandidateBatchMigrationService service;

    @BeforeEach
    void setUp() {
        sap = new FakeSapClient();
        target = new RecordingSmartRecruitersClient();
        records = new InMemoryMigrationRecordRepository();
        jobs = new InMemoryTenantMigrationJobRepository();
        service = batchService(PAGE_SIZE, PARALLELISM);
    }

    @Test
    void shouldMigrateAllPagesAndComplete() {
        sap.addCandidates(8);

        TenantMigrationJob job = service.startOrResume(TENANT);
        service.runJob(job.jobId());

        TenantMigrationJob finished = jobs.get(job.jobId());
        assertEquals(TenantMigrationJobStatus.COMPLETED, finished.status());
        assertEquals(8, finished.processedCount());
        assertEquals(8, finished.succeededCount());
        assertEquals(0, finished.failedCount());
        assertEquals(0, finished.skippedCount());
        assertEquals(List.of(0, 1, 2), sap.requestedPages);
        assertEquals(sap.ids(), Set.copyOf(target.created));
        assertTrue(records.all(MigrationStatus.COMPLETED, sap.ids()));
    }

    @Test
    void shouldReuseCandidatesFromPageWithoutFetchingThemAgain() {
        sap.addCandidates(5);

        service.runJob(service.startOrResume(TENANT).jobId());

        assertEquals(0, sap.singleCandidateRequests.get());
    }

    @Test
    void shouldPersistCheckpointAfterEveryPage() {
        sap.addCandidates(7);

        TenantMigrationJob job = service.startOrResume(TENANT);
        service.runJob(job.jobId());

        assertEquals(List.of(1, 2, 3), jobs.checkpoints);
        assertEquals(3, jobs.get(job.jobId()).nextPage());
    }

    @Test
    void shouldCompleteEmptyTenant() {
        TenantMigrationJob job = service.startOrResume(TENANT);
        service.runJob(job.jobId());

        TenantMigrationJob finished = jobs.get(job.jobId());
        assertEquals(TenantMigrationJobStatus.COMPLETED, finished.status());
        assertEquals(0, finished.processedCount());
        assertEquals(List.of(0), sap.requestedPages);
    }

    @Test
    void shouldContinueAfterFailedCandidatesAndCompleteWithErrors() {
        sap.addCandidates(7);
        // invalid in the source (validation) and rejected by the target, both on the first page
        sap.replace(new SapCandidate("candidate-02", TENANT, "No", "Email", ""));
        target.rejected.add("candidate-03");

        TenantMigrationJob job = service.startOrResume(TENANT);
        service.runJob(job.jobId());

        TenantMigrationJob finished = jobs.get(job.jobId());
        assertEquals(TenantMigrationJobStatus.COMPLETED_WITH_ERRORS, finished.status());
        assertEquals(7, finished.processedCount());
        assertEquals(5, finished.succeededCount());
        assertEquals(2, finished.failedCount());
        assertEquals(List.of(0, 1, 2), sap.requestedPages);
        assertEquals(MigrationStatus.FAILED, records.status("candidate-02"));
        assertEquals(MigrationStatus.FAILED, records.status("candidate-03"));
        assertEquals(MigrationStatus.COMPLETED, records.status("candidate-01"));
        assertEquals(MigrationStatus.COMPLETED, records.status("candidate-07"));
    }

    @Test
    void shouldResumeFromCheckpoint() {
        sap.addCandidates(8);
        TenantMigrationJob interrupted = jobs.newJob(TENANT, PAGE_SIZE, 2);

        service.runJob(interrupted.jobId());

        assertEquals(List.of(2), sap.requestedPages);
        assertEquals(Set.of("candidate-07", "candidate-08"), Set.copyOf(target.created));
        assertEquals(TenantMigrationJobStatus.COMPLETED, jobs.get(interrupted.jobId()).status());
    }

    @Test
    void shouldCountCandidatesMigratedBeforeCrashAsSucceededWhenPageIsReplayed() {
        sap.addCandidates(5);
        TenantMigrationJob job = service.startOrResume(TENANT);

        // page 0 is fully migrated, then the process dies before the checkpoint is saved
        jobs.crashOnNextCheckpoint = true;
        assertThrows(InMemoryTenantMigrationJobRepository.SimulatedCrash.class, () -> service.runJob(job.jobId()));

        TenantMigrationJob crashed = jobs.get(job.jobId());
        assertEquals(TenantMigrationJobStatus.RUNNING, crashed.status());
        assertEquals(0, crashed.nextPage());
        assertEquals(0, crashed.processedCount());
        assertTrue(records.all(MigrationStatus.COMPLETED, Set.of("candidate-01", "candidate-02", "candidate-03")));
        assertEquals(List.of("candidate-01", "candidate-02", "candidate-03"), target.created.stream().sorted().toList());

        // restart: lease of the dead worker expires, a new instance resumes the same page
        jobs.expireLease(job.jobId());
        batchService(PAGE_SIZE, PARALLELISM).runJob(job.jobId());

        TenantMigrationJob finished = jobs.get(job.jobId());
        assertEquals(List.of(0, 0, 1), sap.requestedPages);
        assertEquals(TenantMigrationJobStatus.COMPLETED, finished.status());
        assertEquals(5, finished.processedCount());
        assertEquals(5, finished.succeededCount());
        assertEquals(0, finished.skippedCount());
        assertEquals(0, finished.failedCount());
        // replay did not send anything twice
        assertEquals(5, target.created.size());
        assertEquals(sap.ids(), Set.copyOf(target.created));
    }

    @Test
    void shouldCountCandidateHeldByAnotherWorkerAsSkipped() {
        sap.addCandidates(3);
        records.inProgressElsewhere("candidate-02");

        TenantMigrationJob job = service.startOrResume(TENANT);
        service.runJob(job.jobId());

        TenantMigrationJob finished = jobs.get(job.jobId());
        assertEquals(TenantMigrationJobStatus.COMPLETED, finished.status());
        assertEquals(3, finished.processedCount());
        assertEquals(2, finished.succeededCount());
        assertEquals(1, finished.skippedCount());
        assertEquals(Set.of("candidate-01", "candidate-03"), Set.copyOf(target.created));
    }

    @Test
    void shouldCountCandidateTakenOverFromStaleClaimAsSkippedNotSucceeded() {
        sap.addCandidates(3);
        // the target call for candidate-02 outlives its claim; another worker takes the record over
        target.onCreateOf = externalId -> {
            if (externalId.equals("candidate-02")) {
                records.takeOver("candidate-02");
            }
        };

        TenantMigrationJob job = service.startOrResume(TENANT);
        service.runJob(job.jobId());

        TenantMigrationJob finished = jobs.get(job.jobId());
        assertEquals(TenantMigrationJobStatus.COMPLETED, finished.status());
        assertEquals(3, finished.processedCount());
        assertEquals(2, finished.succeededCount());
        assertEquals(1, finished.skippedCount());
        assertEquals(0, finished.failedCount());
        // this job neither completed nor failed the record it no longer owned
        assertEquals(MigrationStatus.IN_PROGRESS, records.status("candidate-02"));
    }

    @Test
    void shouldRetryPreviouslyFailedCandidatesInNextJob() {
        sap.addCandidates(4);
        target.rejected.add("candidate-02");
        TenantMigrationJob first = service.startOrResume(TENANT);
        service.runJob(first.jobId());
        assertEquals(TenantMigrationJobStatus.COMPLETED_WITH_ERRORS, jobs.get(first.jobId()).status());

        target.rejected.clear();
        target.created.clear();
        TenantMigrationJob second = service.startOrResume(TENANT);
        service.runJob(second.jobId());

        assertEquals(List.of("candidate-02"), target.created);
        TenantMigrationJob retried = jobs.get(second.jobId());
        assertEquals(TenantMigrationJobStatus.COMPLETED, retried.status());
        // the job summary describes the tenant's final result, not only what this run sent
        assertEquals(4, retried.succeededCount());
        assertEquals(0, retried.skippedCount());
        assertEquals(0, retried.failedCount());
    }

    @Test
    void shouldFailJobAndKeepCheckpointWhenPageCannotBeRead() {
        sap.addCandidates(8);
        sap.failingPage = 1;

        TenantMigrationJob job = service.startOrResume(TENANT);
        service.runJob(job.jobId());

        TenantMigrationJob failed = jobs.get(job.jobId());
        assertEquals(TenantMigrationJobStatus.FAILED, failed.status());
        assertEquals(1, failed.nextPage());
        assertEquals(3, failed.processedCount());
        assertNotNull(failed.lastError());

        // SAP is back: the same job continues from page 1
        sap.failingPage = -1;
        assertEquals(job.jobId(), service.startOrResume(TENANT).jobId());
        service.runJob(job.jobId());

        TenantMigrationJob resumed = jobs.get(job.jobId());
        assertEquals(TenantMigrationJobStatus.COMPLETED, resumed.status());
        assertEquals(8, resumed.processedCount());
        assertEquals(List.of(0, 1, 1, 2), sap.requestedPages);
    }

    @Test
    void shouldNotRunJobThatCannotBeClaimed() {
        sap.addCandidates(3);
        TenantMigrationJob job = service.startOrResume(TENANT);
        service.runJob(job.jobId());
        sap.requestedPages.clear();

        service.runJob(job.jobId());

        assertEquals(List.of(), sap.requestedPages);
        assertEquals(TenantMigrationJobStatus.COMPLETED, jobs.get(job.jobId()).status());
    }

    @Test
    void shouldStopWhenLeaseIsLost() {
        sap.addCandidates(9);
        TenantMigrationJob job = service.startOrResume(TENANT);
        target.onCreate = () -> jobs.stealLease(job.jobId());

        service.runJob(job.jobId());

        // page 0 processed, checkpoint rejected -> no further pages, no status change by this worker
        assertEquals(List.of(0), sap.requestedPages);
        assertEquals(List.of(), jobs.checkpoints);
        assertEquals(TenantMigrationJobStatus.RUNNING, jobs.get(job.jobId()).status());
    }

    @Test
    void shouldNeverExceedConfiguredParallelism() {
        int pageSize = 40;
        sap.addCandidates(100);
        target.delayMillis = 10;
        service = batchService(pageSize, PARALLELISM);

        TenantMigrationJob job = service.startOrResume(TENANT);
        service.runJob(job.jobId());

        assertTrue(target.maxInFlight.get() <= PARALLELISM, "max in flight " + target.maxInFlight.get());
        assertTrue(target.maxInFlight.get() > 1, "candidates were not processed in parallel");
        assertEquals(100, jobs.get(job.jobId()).succeededCount());
    }

    @Test
    void shouldProcessSequentiallyWithParallelismOne() {
        sap.addCandidates(10);
        target.delayMillis = 2;
        service = batchService(5, 1);

        service.runJob(service.startOrResume(TENANT).jobId());

        assertEquals(1, target.maxInFlight.get());
        assertEquals(sap.ids(), Set.copyOf(target.created));
    }

    private CandidateBatchMigrationService batchService(int pageSize, int parallelism) {
        CandidateMigrationService candidateMigrationService = new CandidateMigrationService(
                sap, new CandidateMapper(), new CandidateValidator(), target, records
        );
        return new CandidateBatchMigrationService(sap, candidateMigrationService, jobs, pageSize, parallelism);
    }

    private static final class FakeSapClient implements SapClient {

        private final List<SapCandidate> candidates = new ArrayList<>();
        final List<Integer> requestedPages = new CopyOnWriteArrayList<>();
        final AtomicInteger singleCandidateRequests = new AtomicInteger();
        volatile int failingPage = -1;

        void addCandidates(int count) {
            IntStream.rangeClosed(1, count).forEach(i -> candidates.add(new SapCandidate(
                    "candidate-%02d".formatted(i), TENANT, "First" + i, "Last" + i, "person" + i + "@example.com"
            )));
        }

        void replace(SapCandidate candidate) {
            candidates.replaceAll(existing -> existing.id().equals(candidate.id()) ? candidate : existing);
        }

        Set<String> ids() {
            Set<String> ids = new HashSet<>();
            candidates.forEach(candidate -> ids.add(candidate.id()));
            return ids;
        }

        @Override
        public SapCandidate getCandidate(String tenantId, String candidateId) {
            singleCandidateRequests.incrementAndGet();
            throw new AssertionError("batch migration must not fetch single candidates");
        }

        @Override
        public SapCandidatePage getCandidates(String tenantId, int page, int size) {
            requestedPages.add(page);
            if (page == failingPage) {
                throw new ExternalSystemException(
                        "SAP GET candidates", FailureType.TRANSIENT, new ResourceAccessException("Read timed out")
                );
            }
            int from = Math.min(page * size, candidates.size());
            int to = Math.min(from + size, candidates.size());
            return new SapCandidatePage(List.copyOf(candidates.subList(from, to)), page, size, to < candidates.size());
        }
    }

    private static final class RecordingSmartRecruitersClient implements SmartRecruitersClient {

        final List<String> created = new CopyOnWriteArrayList<>();
        final Set<String> rejected = ConcurrentHashMap.newKeySet();
        final AtomicInteger inFlight = new AtomicInteger();
        final AtomicInteger maxInFlight = new AtomicInteger();
        volatile long delayMillis;
        volatile Runnable onCreate = () -> {
        };
        volatile Consumer<String> onCreateOf = externalId -> {
        };

        @Override
        public void createCandidate(String tenantId, SmartRecruitersCandidateRequest request) {
            int current = inFlight.incrementAndGet();
            maxInFlight.accumulateAndGet(current, Math::max);
            try {
                if (delayMillis > 0) {
                    Thread.sleep(delayMillis);
                }
                onCreate.run();
                onCreateOf.accept(request.externalId());
                if (rejected.contains(request.externalId())) {
                    throw new IllegalStateException("Target rejected " + request.externalId());
                }
                created.add(request.externalId());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            } finally {
                inFlight.decrementAndGet();
            }
        }

        @Override
        public void upsertCandidate(String tenantId, SmartRecruitersCandidateRequest request) {
            throw new AssertionError("initial load must use create-if-absent, not upsert");
        }

        @Override
        public SmartRecruitersCandidatePage getCandidates(String tenantId, int page, int size) {
            throw new AssertionError("migration must not list target candidates");
        }
    }

    /**
     * Claim semantics of the JDBC repository without lease expiry.
     */
    private static final class InMemoryMigrationRecordRepository implements MigrationRecordRepository {

        private final Map<String, MigrationStatus> statuses = Collections.synchronizedMap(new HashMap<>());
        private final Map<String, UUID> owners = Collections.synchronizedMap(new HashMap<>());

        @Override
        public boolean tryStart(String tenantId, String sourceRecordId, UUID leaseOwner) {
            synchronized (statuses) {
                MigrationStatus current = statuses.get(sourceRecordId);
                if (current == null || current == MigrationStatus.FAILED) {
                    statuses.put(sourceRecordId, MigrationStatus.IN_PROGRESS);
                    owners.put(sourceRecordId, leaseOwner);
                    return true;
                }
                return false;
            }
        }

        @Override
        public boolean markCompleted(String tenantId, String sourceRecordId, UUID leaseOwner) {
            return finish(sourceRecordId, leaseOwner, MigrationStatus.COMPLETED);
        }

        @Override
        public boolean markFailed(String tenantId, String sourceRecordId, UUID leaseOwner) {
            return finish(sourceRecordId, leaseOwner, MigrationStatus.FAILED);
        }

        private boolean finish(String sourceRecordId, UUID leaseOwner, MigrationStatus status) {
            synchronized (statuses) {
                if (statuses.get(sourceRecordId) != MigrationStatus.IN_PROGRESS
                        || !leaseOwner.equals(owners.get(sourceRecordId))) {
                    return false;
                }
                statuses.put(sourceRecordId, status);
                owners.remove(sourceRecordId);
                return true;
            }
        }

        @Override
        public Optional<MigrationStatus> findStatus(String tenantId, String sourceRecordId) {
            return Optional.ofNullable(statuses.get(sourceRecordId));
        }

        void inProgressElsewhere(String id) {
            statuses.put(id, MigrationStatus.IN_PROGRESS);
            owners.put(id, UUID.randomUUID());
        }

        /**
         * The claim of the worker currently processing {@code id} expired and another worker took it over.
         */
        void takeOver(String id) {
            owners.put(id, UUID.randomUUID());
        }

        void complete(String... ids) {
            for (String id : ids) {
                statuses.put(id, MigrationStatus.COMPLETED);
            }
        }

        MigrationStatus status(String id) {
            return statuses.get(id);
        }

        boolean all(MigrationStatus status, Set<String> ids) {
            return ids.stream().allMatch(id -> statuses.get(id) == status);
        }
    }
}
