package com.dch.srstub;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;

@RestController
@RequestMapping("/api/tenants/{tenantId}/candidates")
public class CandidateController {

    private final CandidateStore store;

    public CandidateController(CandidateStore store) {
        this.store = store;
    }

    @PostMapping
    public ResponseEntity<StoredCandidate> createCandidate(
            @PathVariable String tenantId,
            @RequestBody SmartRecruitersCandidateRequest request
    ) {
        if (request.externalId() == null || request.externalId().isBlank()) {
            return ResponseEntity.badRequest().build();
        }

        CandidateStore.CreateResult result = store.create(tenantId, request);

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
