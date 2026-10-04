package com.dch.smartrecruters.api;

import com.dch.smartrecruters.service.CandidateReconciliationService;
import com.dch.smartrecruters.service.ReconciliationRunLauncher;
import com.dch.smartrecruters.state.ReconciliationItem;
import com.dch.smartrecruters.state.ReconciliationItemPage;
import com.dch.smartrecruters.state.ReconciliationResult;
import com.dch.smartrecruters.state.ReconciliationRun;
import com.dch.smartrecruters.state.ReconciliationRunStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ReconciliationControllerTest {

    private static final UUID RUN_ID = UUID.fromString("0b6f1d2e-3c4b-4a5d-8e7f-9a0b1c2d3e4f");

    private CandidateReconciliationService reconciliationService;
    private ReconciliationRunLauncher runLauncher;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        reconciliationService = mock(CandidateReconciliationService.class);
        runLauncher = mock(ReconciliationRunLauncher.class);
        mockMvc = MockMvcBuilders
                .standaloneSetup(new ReconciliationController(reconciliationService, runLauncher))
                .build();
    }

    @Test
    void shouldStartRunInBackgroundAndReturnAccepted() throws Exception {
        when(reconciliationService.startOrGetUnfinished("tenant-1")).thenReturn(run(ReconciliationRunStatus.PENDING));

        mockMvc.perform(post("/api/reconciliation/tenant-1/candidates"))
                .andExpect(status().isAccepted())
                .andExpect(header().string("Location", "/api/reconciliation/runs/" + RUN_ID))
                .andExpect(jsonPath("$.runId").value(RUN_ID.toString()))
                .andExpect(jsonPath("$.status").value("PENDING"));

        verify(runLauncher).submit(RUN_ID);
    }

    @Test
    void shouldReturnRunSummary() throws Exception {
        when(reconciliationService.findRun(RUN_ID)).thenReturn(Optional.of(run(ReconciliationRunStatus.COMPLETED)));

        mockMvc.perform(get("/api/reconciliation/runs/" + RUN_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.sourceCount").value(10))
                .andExpect(jsonPath("$.targetCount").value(9))
                .andExpect(jsonPath("$.matchedCount").value(7))
                .andExpect(jsonPath("$.missingInTargetCount").value(2))
                .andExpect(jsonPath("$.unexpectedInTargetCount").value(1))
                .andExpect(jsonPath("$.mismatchedCount").value(1));
    }

    @Test
    void shouldReturnNotFoundForUnknownRun() throws Exception {
        when(reconciliationService.findRun(any())).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/reconciliation/runs/" + RUN_ID)).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/reconciliation/runs/" + RUN_ID + "/items")).andExpect(status().isNotFound());
    }

    @Test
    void shouldReturnPagedDifferencesOfCompletedRun() throws Exception {
        when(reconciliationService.findRun(RUN_ID)).thenReturn(Optional.of(run(ReconciliationRunStatus.COMPLETED)));
        when(reconciliationService.findItems(RUN_ID, ReconciliationResult.MISMATCHED, 1, 50)).thenReturn(
                new ReconciliationItemPage(
                        List.of(new ReconciliationItem("c7", ReconciliationResult.MISMATCHED, "a".repeat(64), "b".repeat(64))),
                        1, 50, false
                ));

        mockMvc.perform(get("/api/reconciliation/runs/" + RUN_ID + "/items")
                        .param("result", "MISMATCHED").param("page", "1").param("size", "50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].externalId").value("c7"))
                .andExpect(jsonPath("$.items[0].result").value("MISMATCHED"))
                .andExpect(jsonPath("$.items[0].sourceFingerprint").value("a".repeat(64)))
                .andExpect(jsonPath("$.items[0].targetFingerprint").value("b".repeat(64)))
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.hasNext").value(false));
    }

    @Test
    void shouldDefaultToAllDifferencesAndFirstPage() throws Exception {
        when(reconciliationService.findRun(RUN_ID)).thenReturn(Optional.of(run(ReconciliationRunStatus.COMPLETED)));
        when(reconciliationService.findItems(RUN_ID, null, 0, 100))
                .thenReturn(new ReconciliationItemPage(List.of(), 0, 100, false));

        mockMvc.perform(get("/api/reconciliation/runs/" + RUN_ID + "/items")).andExpect(status().isOk());

        verify(reconciliationService).findItems(RUN_ID, null, 0, 100);
    }

    @Test
    void shouldRejectItemsOfUnfinishedRun() throws Exception {
        when(reconciliationService.findRun(RUN_ID)).thenReturn(Optional.of(run(ReconciliationRunStatus.RUNNING)));

        mockMvc.perform(get("/api/reconciliation/runs/" + RUN_ID + "/items")).andExpect(status().isConflict());
    }

    @Test
    void shouldRejectUnboundedOrInvalidPaging() throws Exception {
        mockMvc.perform(get("/api/reconciliation/runs/" + RUN_ID + "/items").param("size", "1001"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/reconciliation/runs/" + RUN_ID + "/items").param("size", "0"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/reconciliation/runs/" + RUN_ID + "/items").param("page", "-1"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/reconciliation/runs/" + RUN_ID + "/items").param("result", "SOMETHING"))
                .andExpect(status().isBadRequest());

        verify(reconciliationService, never()).findItems(any(), any(), anyInt(), anyInt());
    }

    private ReconciliationRun run(ReconciliationRunStatus status) {
        return new ReconciliationRun(
                RUN_ID, "tenant-1", status, 100,
                10, 9, 7, 2, 1, 1, null,
                Instant.parse("2026-01-01T10:00:00Z"), Instant.parse("2026-01-01T10:05:00Z"),
                status == ReconciliationRunStatus.COMPLETED ? Instant.parse("2026-01-01T10:05:00Z") : null
        );
    }
}
