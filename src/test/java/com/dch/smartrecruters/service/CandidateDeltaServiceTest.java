package com.dch.smartrecruters.service;

import com.dch.smartrecruters.client.ExternalSystemException;
import com.dch.smartrecruters.client.FailureType;
import com.dch.smartrecruters.client.SapClient;
import com.dch.smartrecruters.client.SmartRecruitersClient;
import com.dch.smartrecruters.client.sap.SapCandidate;
import com.dch.smartrecruters.client.sap.SapCandidatePage;
import com.dch.smartrecruters.client.smartrecruiters.SmartRecruitersCandidatePage;
import com.dch.smartrecruters.client.smartrecruiters.SmartRecruitersCandidateRequest;
import com.dch.smartrecruters.domain.Candidate;
import com.dch.smartrecruters.mapper.CandidateMapper;
import com.dch.smartrecruters.messaging.CandidateChangedEvent;
import com.dch.smartrecruters.state.CandidateDeltaEventRepository;
import com.dch.smartrecruters.state.DeltaEventRecord;
import com.dch.smartrecruters.state.MigrationStatus;
import com.dch.smartrecruters.validation.CandidateValidationException;
import com.dch.smartrecruters.validation.CandidateValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;

import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real mapping/validation with in-memory SAP, target (keyed by tenantId + externalId) and event inbox.
 */
class CandidateDeltaServiceTest {

    private static final String TENANT = "tenant-1";
    private static final String CANDIDATE = "candidate-1";

    private FakeSapClient sap;
    private FakeTarget target;
    private InMemoryDeltaEventRepository events;
    private CandidateDeltaService service;

    @BeforeEach
    void setUp() {
        sap = new FakeSapClient();
        target = new FakeTarget();
        events = new InMemoryDeltaEventRepository();
        service = new CandidateDeltaService(sap, new CandidateMapper(), new CandidateValidator(), target, events);

        sap.put(new SapCandidate(CANDIDATE, TENANT, "John", "Smith", "john@example.com"));
    }

    @Test
    void shouldProcessFirstEventWithLatestSourceState() {
        CandidateChangedEvent event = event(TENANT, CANDIDATE);

        assertEquals(DeltaEventOutcome.PROCESSED, service.process(event));

        assertEquals("john@example.com", target.get(TENANT, CANDIDATE).email());
        assertEquals(MigrationStatus.COMPLETED, events.status(event.eventId()));
    }

    @Test
    void shouldIgnoreDuplicateDeliveryOfSameEvent() {
        CandidateChangedEvent event = event(TENANT, CANDIDATE);
        service.process(event);

        assertEquals(DeltaEventOutcome.DUPLICATE, service.process(event));

        assertEquals(1, sap.reads);
        assertEquals(1, target.writes);
    }

    @Test
    void shouldNotReprocessCompletedEventEvenIfSourceChangedSince() {
        CandidateChangedEvent event = event(TENANT, CANDIDATE);
        service.process(event);
        sap.put(new SapCandidate(CANDIDATE, TENANT, "John", "Smith", "changed@example.com"));

        assertEquals(DeltaEventOutcome.DUPLICATE, service.process(event));

        // only a NEW event brings the new state over
        assertEquals("john@example.com", target.get(TENANT, CANDIDATE).email());
    }

    @Test
    void shouldProcessDifferentEventsForSameCandidateAndUpdateWithoutDuplicate() {
        service.process(event(TENANT, CANDIDATE));

        sap.put(new SapCandidate(CANDIDATE, TENANT, "John", "Smith", "new@example.com"));
        assertEquals(DeltaEventOutcome.PROCESSED, service.process(event(TENANT, CANDIDATE)));

        sap.put(new SapCandidate(CANDIDATE, TENANT, "John", "Doe", "new@example.com"));
        assertEquals(DeltaEventOutcome.PROCESSED, service.process(event(TENANT, CANDIDATE)));

        assertEquals(1, target.size(TENANT));
        assertEquals(new SmartRecruitersCandidateRequest(CANDIDATE, "John", "Doe", "new@example.com"),
                target.get(TENANT, CANDIDATE));
        assertEquals(3, target.writes);
    }

    @Test
    void shouldUpdateCandidateCreatedByInitialLoad() {
        target.createCandidate(TENANT, new SmartRecruitersCandidateRequest(CANDIDATE, "John", "Smith", "old@example.com"));

        service.process(event(TENANT, CANDIDATE));

        assertEquals(1, target.size(TENANT));
        assertEquals("john@example.com", target.get(TENANT, CANDIDATE).email());
    }

    @Test
    void shouldMarkEventFailedAndPropagateTransientFailureForKafkaRetry() {
        CandidateChangedEvent event = event(TENANT, CANDIDATE);
        ExternalSystemException unavailable = transientFailure();
        target.failNext(unavailable);

        RuntimeException thrown = assertThrows(RuntimeException.class, () -> service.process(event));

        // not wrapped into a permanent failure: Kafka retries it
        assertSame(unavailable, thrown);
        assertEquals(MigrationStatus.FAILED, events.status(event.eventId()));
    }

    @Test
    void shouldReclaimFailedEventOnRedelivery() {
        CandidateChangedEvent event = event(TENANT, CANDIDATE);
        target.failNext(transientFailure());
        assertThrows(ExternalSystemException.class, () -> service.process(event));

        assertEquals(DeltaEventOutcome.PROCESSED, service.process(event));

        assertEquals(MigrationStatus.COMPLETED, events.status(event.eventId()));
        assertEquals(2, events.get(event.eventId()).attempts());
        assertEquals("john@example.com", target.get(TENANT, CANDIDATE).email());
    }

    @Test
    void shouldNotProcessEventHeldByAnotherWorker() {
        CandidateChangedEvent event = event(TENANT, CANDIDATE);
        events.tryClaim(event.eventId(), TENANT, CANDIDATE, event.occurredAt(), UUID.randomUUID());

        assertThrows(DeltaEventInProgressException.class, () -> service.process(event));

        assertEquals(0, sap.reads);
        assertEquals(0, target.writes);
        assertEquals(MigrationStatus.IN_PROGRESS, events.status(event.eventId()));
    }

    @Test
    void shouldReclaimEventAbandonedByDeadWorker() {
        CandidateChangedEvent event = event(TENANT, CANDIDATE);
        events.tryClaim(event.eventId(), TENANT, CANDIDATE, event.occurredAt(), UUID.randomUUID());
        events.expireLease(event.eventId());

        assertEquals(DeltaEventOutcome.PROCESSED, service.process(event));
        assertEquals(MigrationStatus.COMPLETED, events.status(event.eventId()));
    }

    @Test
    void shouldNotCompleteEventWhoseLeaseWasReclaimedWhileProcessing() {
        CandidateChangedEvent event = event(TENANT, CANDIDATE);
        // worker A stalls during the target call long enough for its lease to be reclaimed by worker B
        target.onUpsert(() -> events.reclaimByOtherWorker(event.eventId()));

        assertThrows(DeltaEventLeaseLostException.class, () -> service.process(event));

        // A did not report success and did not touch B's claim
        DeltaEventRecord record = events.get(event.eventId());
        assertEquals(MigrationStatus.IN_PROGRESS, record.status());
        assertEquals(2, record.attempts());
    }

    @Test
    void shouldNotFailEventWhoseLeaseWasReclaimedAndNeverDeadLetterItDirectly() {
        CandidateChangedEvent event = event(TENANT, CANDIDATE);
        ExternalSystemException rejected = ExternalSystemException.fromHttpClientFailure(
                "SmartRecruiters PUT candidate", FailureType.PERMANENT, new HttpClientErrorException(HttpStatus.BAD_REQUEST));
        target.onUpsert(() -> events.reclaimByOtherWorker(event.eventId()));
        target.failNext(rejected);

        DeltaEventLeaseLostException thrown =
                assertThrows(DeltaEventLeaseLostException.class, () -> service.process(event));

        // the would-be permanent failure is kept for diagnostics but is not the cause,
        // so the Kafka classifier treats the lease loss as retryable instead of dead-lettering B's event
        assertNull(thrown.getCause());
        assertSame(rejected, thrown.getSuppressed()[0]);
        assertEquals(MigrationStatus.IN_PROGRESS, events.status(event.eventId()));
    }

    @Test
    void shouldTreatRedeliveryAfterLostLeaseAsDuplicateOnceNewOwnerCompleted() {
        CandidateChangedEvent event = event(TENANT, CANDIDATE);
        UUID[] workerB = new UUID[1];
        target.onUpsert(() -> workerB[0] = events.reclaimByOtherWorker(event.eventId()));
        assertThrows(DeltaEventLeaseLostException.class, () -> service.process(event));
        target.onUpsert(() -> {
        });

        assertTrue(events.markCompleted(event.eventId(), workerB[0]));

        assertEquals(DeltaEventOutcome.DUPLICATE, service.process(event));
    }

    @Test
    void shouldTreatValidationFailureAsPermanent() {
        sap.put(new SapCandidate(CANDIDATE, TENANT, "John", "Smith", ""));
        CandidateChangedEvent event = event(TENANT, CANDIDATE);

        PermanentDeltaEventException thrown =
                assertThrows(PermanentDeltaEventException.class, () -> service.process(event));

        CandidateValidationException cause = assertInstanceOf(CandidateValidationException.class, thrown.getCause());
        assertEquals("Candidate email is required", cause.getMessage());
        assertEquals(0, target.writes);
        assertEquals(MigrationStatus.FAILED, events.status(event.eventId()));
        assertEquals("Candidate email is required", events.get(event.eventId()).lastError());
    }

    @Test
    void shouldKeepGenericIllegalArgumentExceptionRetryable() {
        IllegalArgumentException bug = new IllegalArgumentException("unexpected mapping state");
        FailingOnceMapper mapper = new FailingOnceMapper(bug);
        service = new CandidateDeltaService(sap, mapper, new CandidateValidator(), target, events);
        CandidateChangedEvent event = event(TENANT, CANDIDATE);

        RuntimeException thrown = assertThrows(RuntimeException.class, () -> service.process(event));

        // propagated unchanged, NOT classified as a permanent business failure -> Kafka retries it
        assertSame(bug, thrown);
        assertEquals(MigrationStatus.FAILED, events.status(event.eventId()));
        assertEquals(0, target.writes);

        // the redelivery reclaims the FAILED event and succeeds once the problem is gone
        assertEquals(DeltaEventOutcome.PROCESSED, service.process(event));
        assertEquals(MigrationStatus.COMPLETED, events.status(event.eventId()));
    }

    @Test
    void shouldTreatPermanentExternalFailureAsPermanent() {
        sap.failNext(ExternalSystemException.fromHttpClientFailure(
                "SAP GET candidate", FailureType.PERMANENT, new HttpClientErrorException(HttpStatus.NOT_FOUND)
        ));
        CandidateChangedEvent event = event(TENANT, CANDIDATE);

        PermanentDeltaEventException thrown =
                assertThrows(PermanentDeltaEventException.class, () -> service.process(event));

        assertInstanceOf(ExternalSystemException.class, thrown.getCause());
        assertEquals(MigrationStatus.FAILED, events.status(event.eventId()));
    }

    @Test
    void shouldRejectIncompleteEventWithoutClaimingIt() {
        CandidateChangedEvent incomplete = new CandidateChangedEvent(UUID.randomUUID(), TENANT, null, Instant.now());

        assertThrows(PermanentDeltaEventException.class, () -> service.process(incomplete));
        assertThrows(PermanentDeltaEventException.class, () -> service.process(null));

        assertTrue(events.isEmpty());
        assertEquals(0, sap.reads);
    }

    @Test
    void shouldKeepTenantsIsolated() {
        sap.put(new SapCandidate(CANDIDATE, "tenant-2", "Maria", "Garcia", "maria@example.com"));

        service.process(event(TENANT, CANDIDATE));
        service.process(event("tenant-2", CANDIDATE));

        assertEquals("john@example.com", target.get(TENANT, CANDIDATE).email());
        assertEquals("maria@example.com", target.get("tenant-2", CANDIDATE).email());
        assertEquals(1, target.size(TENANT));
        assertEquals(1, target.size("tenant-2"));
    }

    @Test
    void shouldRejectEventIdReusedForOtherTenant() {
        sap.put(new SapCandidate(CANDIDATE, "tenant-2", "Maria", "Garcia", "maria@example.com"));
        CandidateChangedEvent original = event(TENANT, CANDIDATE);
        service.process(original);

        CandidateChangedEvent reused =
                new CandidateChangedEvent(original.eventId(), "tenant-2", CANDIDATE, Instant.now());

        assertThrows(PermanentDeltaEventException.class, () -> service.process(reused));
        assertEquals(0, target.size("tenant-2"));
        assertEquals(TENANT, events.get(original.eventId()).tenantId());
    }

    @Test
    void shouldNeverWriteSourceRecordOfOtherTenant() {
        sap.answerWith(new SapCandidate(CANDIDATE, "tenant-2", "Maria", "Garcia", "maria@example.com"));

        assertThrows(PermanentDeltaEventException.class, () -> service.process(event(TENANT, CANDIDATE)));

        assertEquals(0, target.writes);
    }

    /**
     * A mapping dependency failing with a generic IllegalArgumentException (e.g. a programming error).
     */
    private static final class FailingOnceMapper extends CandidateMapper {

        private RuntimeException failure;

        FailingOnceMapper(RuntimeException failure) {
            this.failure = failure;
        }

        @Override
        public Candidate map(SapCandidate source) {
            if (failure != null) {
                RuntimeException thrown = failure;
                failure = null;
                throw thrown;
            }
            return super.map(source);
        }
    }

    private static CandidateChangedEvent event(String tenantId, String candidateId) {
        return new CandidateChangedEvent(UUID.randomUUID(), tenantId, candidateId, Instant.now());
    }

    private static ExternalSystemException transientFailure() {
        return ExternalSystemException.fromHttpClientFailure(
                "SmartRecruiters PUT candidate", FailureType.TRANSIENT, new ResourceAccessException("Read timed out")
        );
    }

    private static final class FakeSapClient implements SapClient {

        private final Map<String, SapCandidate> candidates = new HashMap<>();
        private RuntimeException nextFailure;
        private SapCandidate forcedAnswer;
        int reads;

        void put(SapCandidate candidate) {
            candidates.put(candidate.tenantId() + ":" + candidate.id(), candidate);
        }

        void failNext(RuntimeException failure) {
            nextFailure = failure;
        }

        void answerWith(SapCandidate candidate) {
            forcedAnswer = candidate;
        }

        @Override
        public SapCandidate getCandidate(String tenantId, String candidateId) {
            reads++;
            if (nextFailure != null) {
                RuntimeException failure = nextFailure;
                nextFailure = null;
                throw failure;
            }
            if (forcedAnswer != null) {
                return forcedAnswer;
            }
            return candidates.get(tenantId + ":" + candidateId);
        }

        @Override
        public SapCandidatePage getCandidates(String tenantId, int page, int size) {
            throw new AssertionError("delta synchronization must not read pages");
        }
    }

    /**
     * Mirrors the target semantics: one candidate per tenantId + externalId.
     */
    private static final class FakeTarget implements SmartRecruitersClient {

        private final Map<String, Map<String, SmartRecruitersCandidateRequest>> candidates = new HashMap<>();
        private RuntimeException nextFailure;
        private Runnable onUpsert = () -> {
        };
        int writes;

        void failNext(RuntimeException failure) {
            nextFailure = failure;
        }

        void onUpsert(Runnable action) {
            onUpsert = action;
        }

        @Override
        public void createCandidate(String tenantId, SmartRecruitersCandidateRequest request) {
            candidates.computeIfAbsent(tenantId, id -> new HashMap<>()).putIfAbsent(request.externalId(), request);
        }

        @Override
        public void upsertCandidate(String tenantId, SmartRecruitersCandidateRequest request) {
            onUpsert.run();
            if (nextFailure != null) {
                RuntimeException failure = nextFailure;
                nextFailure = null;
                throw failure;
            }
            writes++;
            candidates.computeIfAbsent(tenantId, id -> new HashMap<>()).put(request.externalId(), request);
        }

        @Override
        public SmartRecruitersCandidatePage getCandidates(String tenantId, int page, int size) {
            throw new AssertionError("delta synchronization must not list target candidates");
        }

        SmartRecruitersCandidateRequest get(String tenantId, String externalId) {
            return candidates.getOrDefault(tenantId, Map.of()).get(externalId);
        }

        int size(String tenantId) {
            return candidates.getOrDefault(tenantId, Map.of()).size();
        }
    }

    /**
     * Same claim and fencing rules as the JDBC inbox; lease expiry and reclaim are simulated.
     */
    private static final class InMemoryDeltaEventRepository implements CandidateDeltaEventRepository {

        private final Map<UUID, DeltaEventRecord> records = new HashMap<>();
        private final Map<UUID, UUID> owners = new HashMap<>();
        private final Set<UUID> expired = new HashSet<>();

        @Override
        public synchronized boolean tryClaim(
                UUID eventId, String tenantId, String candidateId, Instant occurredAt, UUID leaseOwner
        ) {
            DeltaEventRecord existing = records.get(eventId);
            if (existing == null) {
                records.put(eventId, new DeltaEventRecord(eventId, tenantId, candidateId, MigrationStatus.IN_PROGRESS, 1, null));
                owners.put(eventId, leaseOwner);
                return true;
            }
            boolean sameCandidate = existing.tenantId().equals(tenantId) && existing.candidateId().equals(candidateId);
            boolean claimable = existing.status() == MigrationStatus.FAILED
                    || (existing.status() == MigrationStatus.IN_PROGRESS && expired.remove(eventId));
            if (!sameCandidate || !claimable) {
                return false;
            }
            records.put(eventId, new DeltaEventRecord(
                    eventId, tenantId, candidateId, MigrationStatus.IN_PROGRESS, existing.attempts() + 1, null
            ));
            owners.put(eventId, leaseOwner);
            return true;
        }

        @Override
        public synchronized Optional<DeltaEventRecord> findById(UUID eventId) {
            return Optional.ofNullable(records.get(eventId));
        }

        @Override
        public boolean markCompleted(UUID eventId, UUID leaseOwner) {
            return finish(eventId, leaseOwner, MigrationStatus.COMPLETED, null);
        }

        @Override
        public boolean markFailed(UUID eventId, UUID leaseOwner, String error) {
            return finish(eventId, leaseOwner, MigrationStatus.FAILED, error);
        }

        private synchronized boolean finish(UUID eventId, UUID leaseOwner, MigrationStatus status, String error) {
            DeltaEventRecord current = records.get(eventId);
            if (current == null
                    || current.status() != MigrationStatus.IN_PROGRESS
                    || !leaseOwner.equals(owners.get(eventId))) {
                return false;
            }
            records.put(eventId, new DeltaEventRecord(
                    eventId, current.tenantId(), current.candidateId(), status, current.attempts(), error
            ));
            owners.remove(eventId);
            return true;
        }

        void expireLease(UUID eventId) {
            expired.add(eventId);
        }

        /**
         * Another worker reclaims the event after this worker's lease was considered stale.
         */
        synchronized UUID reclaimByOtherWorker(UUID eventId) {
            DeltaEventRecord current = records.get(eventId);
            expireLease(eventId);
            UUID otherWorker = UUID.randomUUID();
            if (!tryClaim(eventId, current.tenantId(), current.candidateId(), Instant.now(), otherWorker)) {
                throw new AssertionError("reclaim failed");
            }
            return otherWorker;
        }

        MigrationStatus status(UUID eventId) {
            return records.get(eventId).status();
        }

        DeltaEventRecord get(UUID eventId) {
            return records.get(eventId);
        }

        boolean isEmpty() {
            return records.isEmpty();
        }
    }
}
