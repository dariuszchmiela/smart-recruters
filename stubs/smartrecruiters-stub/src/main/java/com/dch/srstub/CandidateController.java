package com.dch.srstub;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;
import java.util.Optional;

@RestController
@RequestMapping("/api/tenants/{tenantId}/candidates")
public class CandidateController {

    private final CandidateStore store;
    private final FailureSimulator failureSimulator;

    public CandidateController(CandidateStore store, FailureSimulator failureSimulator) {
        this.store = store;
        this.failureSimulator = failureSimulator;
    }

    @PostMapping
    public ResponseEntity<StoredCandidate> createCandidate(
            @PathVariable String tenantId,
            @RequestBody SmartRecruitersCandidateRequest request
    ) throws InterruptedException {
        if (request.externalId() == null || request.externalId().isBlank()) {
            return ResponseEntity.badRequest().build();
        }

        Optional<FailureSimulator.FailurePlan> failure = failureSimulator.next();

        if (failure.isPresent() && failure.get().mode() == FailureMode.UNAVAILABLE) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        }

        CandidateStore.CreateResult result = store.create(tenantId, request);

        if (failure.isPresent()) {
            switch (failure.get().mode()) {
                case UNAVAILABLE_AFTER_SAVE -> {
                    return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
                }
                case DELAY_AFTER_SAVE -> Thread.sleep(failure.get().delay());
                case UNAVAILABLE -> throw new IllegalStateException("handled above");
            }
        }

        if (!result.created()) {
            return ResponseEntity.ok(result.candidate());
        }

        URI location = URI.create(
                "/api/tenants/" + tenantId + "/candidates/" + result.candidate().externalId()
        );
        return ResponseEntity.created(location).body(result.candidate());
    }

    @GetMapping("/{externalId}")
    public ResponseEntity<StoredCandidate> getCandidate(
            @PathVariable String tenantId,
            @PathVariable String externalId
    ) {
        return ResponseEntity.of(store.find(tenantId, externalId));
    }

    @GetMapping
    public List<StoredCandidate> getCandidates(@PathVariable String tenantId) {
        return store.findAll(tenantId);
    }
}
