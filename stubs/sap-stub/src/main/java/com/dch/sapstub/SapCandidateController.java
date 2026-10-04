package com.dch.sapstub;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Optional;

@RestController
@RequestMapping("/api/tenants/{tenantId}/candidates")
public class SapCandidateController {

    private static final int MAX_PAGE_SIZE = 1_000;

    private final SapCandidateStore store;
    private final FailureSimulator failureSimulator;

    public SapCandidateController(SapCandidateStore store, FailureSimulator failureSimulator) {
        this.store = store;
        this.failureSimulator = failureSimulator;
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
}
