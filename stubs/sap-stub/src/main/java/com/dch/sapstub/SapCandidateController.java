package com.dch.sapstub;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/tenants/{tenantId}/candidates")
public class SapCandidateController {

    private final SapCandidateStore store;

    public SapCandidateController(SapCandidateStore store) {
        this.store = store;
    }

    @GetMapping("/{candidateId}")
    public ResponseEntity<SapCandidate> getCandidate(
            @PathVariable String tenantId,
            @PathVariable String candidateId
    ) {
        return ResponseEntity.of(store.find(tenantId, candidateId));
    }
}
