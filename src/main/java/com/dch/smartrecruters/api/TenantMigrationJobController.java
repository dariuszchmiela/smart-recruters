package com.dch.smartrecruters.api;

import com.dch.smartrecruters.service.CandidateBatchMigrationService;
import com.dch.smartrecruters.service.TenantMigrationJobLauncher;
import com.dch.smartrecruters.state.TenantMigrationJob;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.UUID;

@RestController
@RequestMapping("/api/migrations")
public class TenantMigrationJobController {

    private final CandidateBatchMigrationService batchMigrationService;
    private final TenantMigrationJobLauncher jobLauncher;

    public TenantMigrationJobController(
            CandidateBatchMigrationService batchMigrationService,
            TenantMigrationJobLauncher jobLauncher
    ) {
        this.batchMigrationService = batchMigrationService;
        this.jobLauncher = jobLauncher;
    }

    /**
     * Starts (or resumes the unfinished) candidate migration of the tenant and returns immediately.
     * Progress is available under the returned job location.
     */
    @PostMapping("/{tenantId}/candidates/batch")
    public ResponseEntity<TenantMigrationJobResponse> startCandidateMigration(@PathVariable String tenantId) {
        TenantMigrationJob job = batchMigrationService.startOrResume(tenantId);
        // if the local queue is full the job stays PENDING and the recovery scan starts it later
        jobLauncher.submit(job.jobId());

        return ResponseEntity
                .accepted()
                .location(URI.create("/api/migrations/jobs/" + job.jobId()))
                .body(TenantMigrationJobResponse.from(job));
    }

    @GetMapping("/jobs/{jobId}")
    public ResponseEntity<TenantMigrationJobResponse> getJob(@PathVariable UUID jobId) {
        return ResponseEntity.of(batchMigrationService.findJob(jobId).map(TenantMigrationJobResponse::from));
    }
}
