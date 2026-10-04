package com.dch.smartrecruters.service;

import com.dch.smartrecruters.client.ExternalSystemException;
import com.dch.smartrecruters.client.SapClient;
import com.dch.smartrecruters.client.SmartRecruitersClient;
import com.dch.smartrecruters.client.sap.SapCandidate;
import com.dch.smartrecruters.domain.Candidate;
import com.dch.smartrecruters.mapper.CandidateMapper;
import com.dch.smartrecruters.messaging.CandidateChangedEvent;
import com.dch.smartrecruters.state.CandidateDeltaEventRepository;
import com.dch.smartrecruters.state.DeltaEventRecord;
import com.dch.smartrecruters.state.MigrationStatus;
import com.dch.smartrecruters.validation.CandidateValidationException;
import com.dch.smartrecruters.validation.CandidateValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.UUID;

/**
 * Applies one candidate change to the target:
 * <pre>
 * claim eventId (inbox) -> latest candidate from SAP -> map -> validate -> upsert target -> event COMPLETED
 * </pre>
 * The event only says "something changed"; the data always comes from SAP at processing time,
 * so any order of events converges the target to the latest source state.
 * <p>
 * Failure handling:
 * <ul>
 *   <li>transient (HTTP retries already exhausted, open breaker, DB) - event FAILED, exception
 *       propagated as-is, Kafka redelivers the event (bounded) and the FAILED event is reclaimed</li>
 *   <li>permanent (invalid event, {@link CandidateValidationException}, permanent HTTP error) - event FAILED,
 *       {@link PermanentDeltaEventException}, no Kafka retry, dead letter topic</li>
 *   <li>lease lost (this attempt was considered stale and the event was reclaimed by another worker) -
 *       nothing is written to the inbox, {@link DeltaEventLeaseLostException}, Kafka redelivers and
 *       the inbox state decides (COMPLETED by the new owner -> duplicate)</li>
 * </ul>
 */
public class CandidateDeltaService {

    private static final Logger log = LoggerFactory.getLogger(CandidateDeltaService.class);

    private final SapClient sapClient;
    private final CandidateMapper mapper;
    private final CandidateValidator validator;
    private final SmartRecruitersClient smartRecruitersClient;
    private final CandidateDeltaEventRepository eventRepository;

    public CandidateDeltaService(
            SapClient sapClient,
            CandidateMapper mapper,
            CandidateValidator validator,
            SmartRecruitersClient smartRecruitersClient,
            CandidateDeltaEventRepository eventRepository
    ) {
        this.sapClient = sapClient;
        this.mapper = mapper;
        this.validator = validator;
        this.smartRecruitersClient = smartRecruitersClient;
        this.eventRepository = eventRepository;
    }

    public DeltaEventOutcome process(CandidateChangedEvent event) {
        requireComplete(event);

        // fencing token of this processing attempt; every state change below is conditional on it
        UUID leaseOwner = UUID.randomUUID();

        if (!eventRepository.tryClaim(
                event.eventId(), event.tenantId(), event.candidateId(), event.occurredAt(), leaseOwner)) {
            return notClaimed(event);
        }

        try {
            SapCandidate source = sapClient.getCandidate(event.tenantId(), event.candidateId());
            if (!event.tenantId().equals(source.tenantId()) || !event.candidateId().equals(source.id())) {
                throw new PermanentDeltaEventException(
                        "SAP returned " + source.tenantId() + ":" + source.id() + " for " + describe(event)
                );
            }

            Candidate candidate = mapper.map(source);
            validator.validate(candidate);

            smartRecruitersClient.upsertCandidate(event.tenantId(), mapper.mapToRequest(candidate));
        } catch (RuntimeException e) {
            if (!eventRepository.markFailed(event.eventId(), leaseOwner, e.getMessage())) {
                // not cause: the Kafka classifier inspects the cause chain, and a permanent failure of a
                // stale attempt must not dead-letter an event that another worker now owns
                DeltaEventLeaseLostException lost = new DeltaEventLeaseLostException(event.eventId(), "mark it FAILED");
                lost.addSuppressed(e);
                throw lost;
            }
            throw classify(event, e);
        }

        if (!eventRepository.markCompleted(event.eventId(), leaseOwner)) {
            // the target write itself is harmless (idempotent upsert of the latest source state),
            // but this attempt cannot claim the event as done
            log.warn("Delta event {} was reclaimed by another worker while being processed", event.eventId());
            throw new DeltaEventLeaseLostException(event.eventId(), "mark it COMPLETED");
        }
        return DeltaEventOutcome.PROCESSED;
    }

    private DeltaEventOutcome notClaimed(CandidateChangedEvent event) {
        DeltaEventRecord recorded = eventRepository.findById(event.eventId())
                // claimed and finished/removed in between: let Kafka try again
                .orElseThrow(() -> new DeltaEventInProgressException(event.eventId()));

        if (!recorded.tenantId().equals(event.tenantId()) || !recorded.candidateId().equals(event.candidateId())) {
            throw new PermanentDeltaEventException(
                    "Event id reused: " + event.eventId() + " was recorded for "
                            + recorded.tenantId() + ":" + recorded.candidateId() + ", received for " + describe(event)
            );
        }

        if (recorded.status() == MigrationStatus.COMPLETED) {
            log.info("Delta event {} already processed, ignoring duplicate", event.eventId());
            return DeltaEventOutcome.DUPLICATE;
        }

        // fresh IN_PROGRESS somewhere else: never process it twice in parallel
        throw new DeltaEventInProgressException(event.eventId());
    }

    /**
     * Reuses the transient/permanent classification of the HTTP layer; candidate validation errors
     * ({@link CandidateValidationException}) are permanent. Anything else - including a generic
     * IllegalArgumentException, which may just be a programming error - stays retryable:
     * Kafka retries are bounded, so it ends in the DLT at worst.
     */
    private static RuntimeException classify(CandidateChangedEvent event, RuntimeException e) {
        if (e instanceof PermanentDeltaEventException) {
            return e;
        }
        if (e instanceof ExternalSystemException external && !external.isTransient()) {
            return new PermanentDeltaEventException("Permanent failure for " + describe(event), e);
        }
        if (e instanceof CandidateValidationException) {
            return new PermanentDeltaEventException("Invalid source data for " + describe(event), e);
        }
        return e;
    }

    private static void requireComplete(CandidateChangedEvent event) {
        if (event == null
                || event.eventId() == null
                || isBlank(event.tenantId())
                || isBlank(event.candidateId())
                || event.occurredAt() == null) {
            throw new PermanentDeltaEventException("Incomplete candidate change event: " + event);
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String describe(CandidateChangedEvent event) {
        return "event " + event.eventId() + " (" + Objects.toString(event.tenantId()) + ":" + event.candidateId() + ")";
    }
}
