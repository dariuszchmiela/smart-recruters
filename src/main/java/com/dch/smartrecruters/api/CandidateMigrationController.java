package com.dch.smartrecruters.api;

import com.dch.smartrecruters.service.CandidateMigrationService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/migrations")
public class CandidateMigrationController {

    private final CandidateMigrationService candidateMigrationService;

    public CandidateMigrationController(CandidateMigrationService candidateMigrationService) {
        this.candidateMigrationService = candidateMigrationService;
    }

    @PostMapping("/{tenantId}/candidates/{candidateId}")
    public ResponseEntity<Void> migrateCandidate(
            @PathVariable String tenantId,
            @PathVariable String candidateId
    ) {
        candidateMigrationService.migrateCandidate(tenantId, candidateId);
        return ResponseEntity.noContent().build();
    }
}
