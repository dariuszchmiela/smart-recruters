package com.dch.sapstub;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@RestController
@RequestMapping("/api/tenants/{tenantId}/candidates")
public class SapCandidateController {

    private static final Logger log = LoggerFactory.getLogger(SapCandidateController.class);

    private static final int MAX_PAGE_SIZE = 1_000;

    private final SapCandidateStore store;
    private final FailureSimulator failureSimulator;
    private final CandidateChangePublisher changePublisher;

    public SapCandidateController(
            SapCandidateStore store,
            FailureSimulator failureSimulator,
            CandidateChangePublisher changePublisher
    ) {
        this.store = store;
        this.failureSimulator = failureSimulator;
        this.changePublisher = changePublisher;
    }

    /**
     * Simulates a change in the source system: stores the new data (create or update)
     * and publishes a CandidateChangedEvent. Not subject to failure injection.
     * If publishing fails the change stays stored and 503 is returned.
     */
    @PutMapping("/{candidateId}")
    public ResponseEntity<CandidateChangeResponse> changeCandidate(
            @PathVariable String tenantId,
            @PathVariable String candidateId,
            @RequestBody CandidateChangeRequest request
    ) {
        SapCandidate candidate = new SapCandidate(
                candidateId, tenantId, request.firstName(), request.lastName(), request.email()
        );
        store.save(candidate);

        CandidateChangedEvent event = new CandidateChangedEvent(UUID.randomUUID(), tenantId, candidateId, Instant.now());
        try {
            changePublisher.publish(event);
        } catch (RuntimeException e) {
            log.warn("Candidate {}:{} changed but event {} not published: {}",
                    tenantId, candidateId, event.eventId(), e.getMessage());
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        }

        return ResponseEntity.ok(new CandidateChangeResponse(candidate, event.eventId()));
    }

    @GetMapping
    public ResponseEntity<SapCandidatePage> getCandidates(
            @PathVariable String tenantId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "100") int size
    ) throws InterruptedException {
        if (page < 0 || size < 1 || size > MAX_PAGE_SIZE) {
            return ResponseEntity.badRequest().build();
        }

        Optional<ResponseEntity<SapCandidatePage>> failure = injectFailure();
        if (failure.isPresent()) {
            return failure.get();
        }

        return ResponseEntity.ok(store.findPage(tenantId, page, size));
    }

    @GetMapping("/{candidateId}")
    public ResponseEntity<SapCandidate> getCandidate(
            @PathVariable String tenantId,
            @PathVariable String candidateId
    ) throws InterruptedException {
        Optional<ResponseEntity<SapCandidate>> failure = injectFailure();
        if (failure.isPresent()) {
            return failure.get();
        }

        return ResponseEntity.of(store.find(tenantId, candidateId));
    }

    /**
     * @return the error response to send instead of the real one, if a failure is scheduled
     */
    private <T> Optional<ResponseEntity<T>> injectFailure() throws InterruptedException {
        Optional<FailureSimulator.FailurePlan> failure = failureSimulator.next();

        if (failure.isPresent()) {
            switch (failure.get().mode()) {
                case UNAVAILABLE -> {
                    return Optional.of(ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build());
                }
                case DELAY -> Thread.sleep(failure.get().delay());
            }
        }
        return Optional.empty();
    }

    public record CandidateChangeRequest(String firstName, String lastName, String email) {
    }

    public record CandidateChangeResponse(SapCandidate candidate, UUID eventId) {
    }
}
