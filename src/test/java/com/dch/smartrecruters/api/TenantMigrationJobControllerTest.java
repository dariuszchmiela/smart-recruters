package com.dch.smartrecruters.api;

import com.dch.smartrecruters.service.CandidateBatchMigrationService;
import com.dch.smartrecruters.service.CandidateMigrationService;
import com.dch.smartrecruters.service.TenantMigrationJobLauncher;
import com.dch.smartrecruters.state.TenantMigrationJob;
import com.dch.smartrecruters.state.TenantMigrationJobStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class TenantMigrationJobControllerTest {

    private static final UUID JOB_ID = UUID.fromString("7f8a1c2e-3d4b-4a5c-9e6f-0a1b2c3d4e5f");

    private CandidateBatchMigrationService batchMigrationService;
    private TenantMigrationJobLauncher jobLauncher;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        batchMigrationService = mock(CandidateBatchMigrationService.class);
        jobLauncher = mock(TenantMigrationJobLauncher.class);
        mockMvc = MockMvcBuilders
                .standaloneSetup(
                        new TenantMigrationJobController(batchMigrationService, jobLauncher),
                        new CandidateMigrationController(mock(CandidateMigrationService.class))
                )
                .build();
    }

    @Test
    void shouldStartJobInBackgroundAndReturnAccepted() throws Exception {
        when(batchMigrationService.startOrResume("tenant-1"))
                .thenReturn(job(TenantMigrationJobStatus.PENDING, 0, 0));

        mockMvc.perform(post("/api/migrations/tenant-1/candidates/batch"))
                .andExpect(status().isAccepted())
                .andExpect(header().string("Location", "/api/migrations/jobs/" + JOB_ID))
                .andExpect(jsonPath("$.jobId").value(JOB_ID.toString()))
                .andExpect(jsonPath("$.tenantId").value("tenant-1"))
                .andExpect(jsonPath("$.status").value("PENDING"));

        verify(jobLauncher).submit(JOB_ID);
    }

    @Test
    void shouldReturnJobProgress() throws Exception {
        when(batchMigrationService.findJob(JOB_ID))
                .thenReturn(Optional.of(job(TenantMigrationJobStatus.RUNNING, 3, 300)));

        mockMvc.perform(get("/api/migrations/jobs/" + JOB_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RUNNING"))
                .andExpect(jsonPath("$.nextPage").value(3))
                .andExpect(jsonPath("$.processedCount").value(300))
                .andExpect(jsonPath("$.succeededCount").value(290))
                .andExpect(jsonPath("$.failedCount").value(10));
    }

    @Test
    void shouldReturnNotFoundForUnknownJob() throws Exception {
        when(batchMigrationService.findJob(any())).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/migrations/jobs/" + UUID.randomUUID()))
                .andExpect(status().isNotFound());
    }

    @Test
    void shouldRejectMalformedJobId() throws Exception {
        mockMvc.perform(get("/api/migrations/jobs/not-a-uuid"))
                .andExpect(status().isBadRequest());

        verify(batchMigrationService, never()).findJob(any());
    }

    private TenantMigrationJob job(TenantMigrationJobStatus status, int nextPage, long processed) {
        long failed = processed / 30;
        return new TenantMigrationJob(
                JOB_ID, "tenant-1", status, 100, nextPage,
                processed, processed - failed, 0, failed, null,
                Instant.parse("2026-01-01T10:00:00Z"), Instant.parse("2026-01-01T10:05:00Z")
        );
    }
}
