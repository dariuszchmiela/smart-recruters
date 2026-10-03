package com.dch.sapstub;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Optional;

@RestController
@RequestMapping("/api/tenants/{tenantId}/candidates")
public class SapCandidateController {

    private final SapCandidateStore store;
    private final FailureSimulator failureSimulator;

    public SapCandidateController(SapCandidateStore store, FailureSimulator failureSimulator) {
        this.store = store;
        this.failureSimulator = failureSimulator;
    }

    @GetMapping("/{candidateId}")
    public ResponseEntity<SapCandidate> getCandidate(
            @PathVariable String tenantId,
            @PathVariable String candidateId
    ) throws InterruptedException {
        Optional<FailureSimulator.FailurePlan> failure = failureSimulator.next();

        if (failure.isPresent()) {
            switch (failure.get().mode()) {
                case UNAVAILABLE -> {
                    return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
                }
                case DELAY -> Thread.sleep(failure.get().delay());
            }
        }

        return ResponseEntity.of(store.find(tenantId, candidateId));
    }
}
