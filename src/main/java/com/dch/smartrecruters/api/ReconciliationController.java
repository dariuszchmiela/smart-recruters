package com.dch.smartrecruters.api;

import com.dch.smartrecruters.service.CandidateReconciliationService;
import com.dch.smartrecruters.service.ReconciliationRunLauncher;
import com.dch.smartrecruters.state.ReconciliationItemPage;
import com.dch.smartrecruters.state.ReconciliationResult;
import com.dch.smartrecruters.state.ReconciliationRun;
import com.dch.smartrecruters.state.ReconciliationRunStatus;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.Optional;
import java.util.UUID;

@RestController
@RequestMapping("/api/reconciliation")
public class ReconciliationController {

    static final int MAX_PAGE_SIZE = 1_000;

    private final CandidateReconciliationService reconciliationService;
    private final ReconciliationRunLauncher runLauncher;

    public ReconciliationController(
            CandidateReconciliationService reconciliationService,
            ReconciliationRunLauncher runLauncher
    ) {
        this.reconciliationService = reconciliationService;
        this.runLauncher = runLauncher;
    }

    /**
     * Starts a reconciliation run of the tenant (or returns its unfinished one) and returns immediately.
     */
    @PostMapping("/{tenantId}/candidates")
    public ResponseEntity<ReconciliationRunResponse> startCandidateReconciliation(@PathVariable String tenantId) {
        ReconciliationRun run = reconciliationService.startOrGetUnfinished(tenantId);
        // if the local queue is full the run stays PENDING and the recovery scan starts it later
        runLauncher.submit(run.runId());

        return ResponseEntity
                .accepted()
                .location(URI.create("/api/reconciliation/runs/" + run.runId()))
                .body(ReconciliationRunResponse.from(run));
    }

    @GetMapping("/runs/{runId}")
    public ResponseEntity<ReconciliationRunResponse> getRun(@PathVariable UUID runId) {
        return ResponseEntity.of(reconciliationService.findRun(runId).map(ReconciliationRunResponse::from));
    }

    /**
     * Classified items of a COMPLETED run, ordered by externalId. Without {@code result} every
     * difference (MISSING_IN_TARGET, UNEXPECTED_IN_TARGET, MISMATCHED) is returned.
     * 409 while the run is not COMPLETED (items are only classified at the end).
     */
    @GetMapping("/runs/{runId}/items")
    public ResponseEntity<ReconciliationItemPage> getItems(
            @PathVariable UUID runId,
            @RequestParam(required = false) ReconciliationResult result,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "100") int size
    ) {
        if (page < 0 || size < 1 || size > MAX_PAGE_SIZE) {
            return ResponseEntity.badRequest().build();
        }

        Optional<ReconciliationRun> run = reconciliationService.findRun(runId);
        if (run.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        if (run.get().status() != ReconciliationRunStatus.COMPLETED) {
            return ResponseEntity.status(HttpStatus.CONFLICT).build();
        }

        return ResponseEntity.ok(reconciliationService.findItems(runId, result, page, size));
    }
}
