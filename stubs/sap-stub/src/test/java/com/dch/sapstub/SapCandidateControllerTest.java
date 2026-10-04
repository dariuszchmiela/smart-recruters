package com.dch.sapstub;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class SapCandidateControllerTest {

    private static final String CANDIDATE_1 = "/api/tenants/tenant-1/candidates/candidate-1";

    private FailureSimulator failureSimulator;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        failureSimulator = new FailureSimulator();
        mockMvc = MockMvcBuilders
                .standaloneSetup(
                        new SapCandidateController(new SapCandidateStore(), failureSimulator),
                        new FailureController(failureSimulator)
                )
                .build();
    }

    @Test
    void shouldReturnExistingCandidate() throws Exception {
        mockMvc.perform(get(CANDIDATE_1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("candidate-1"))
                .andExpect(jsonPath("$.tenantId").value("tenant-1"))
                .andExpect(jsonPath("$.firstName").value("John"))
                .andExpect(jsonPath("$.lastName").value("Smith"))
                .andExpect(jsonPath("$.email").value("john.smith@example.com"));
    }

    @Test
    void shouldReturnDifferentCandidateForSameIdInOtherTenant() throws Exception {
        mockMvc.perform(get("/api/tenants/tenant-2/candidates/candidate-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tenantId").value("tenant-2"))
                .andExpect(jsonPath("$.firstName").value("Maria"));
    }

    @Test
    void shouldReturnNotFoundForUnknownCandidate() throws Exception {
        mockMvc.perform(get("/api/tenants/tenant-1/candidates/unknown"))
                .andExpect(status().isNotFound());
    }

    @Test
    void shouldReturnNotFoundForCandidateOfOtherTenant() throws Exception {
        mockMvc.perform(get("/api/tenants/tenant-3/candidates/candidate-1"))
                .andExpect(status().isNotFound());
    }

    @Test
    void shouldReturnFirstPageOrderedByCandidateId() throws Exception {
        mockMvc.perform(get("/api/tenants/tenant-1/candidates").param("page", "0").param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].id").value("candidate-1"))
                .andExpect(jsonPath("$.items[1].id").value("candidate-2"))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(2))
                .andExpect(jsonPath("$.hasNext").value(true));
    }

    @Test
    void shouldReturnLastPage() throws Exception {
        mockMvc.perform(get("/api/tenants/tenant-1/candidates").param("page", "1").param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].id").value("candidate-3"))
                .andExpect(jsonPath("$.hasNext").value(false));
    }

    @Test
    void shouldReturnEmptyPageBeyondLastPageAndForUnknownTenant() throws Exception {
        mockMvc.perform(get("/api/tenants/tenant-1/candidates").param("page", "5").param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(0))
                .andExpect(jsonPath("$.hasNext").value(false));

        mockMvc.perform(get("/api/tenants/unknown/candidates").param("page", "0").param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(0));
    }

    @Test
    void shouldRejectInvalidPageRequest() throws Exception {
        mockMvc.perform(get("/api/tenants/tenant-1/candidates").param("page", "-1").param("size", "2"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/tenants/tenant-1/candidates").param("page", "0").param("size", "0"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void shouldApplyScheduledFailureToPageRequests() throws Exception {
        failureSimulator.schedule(FailureMode.UNAVAILABLE, 1, Duration.ZERO);

        mockMvc.perform(get("/api/tenants/tenant-1/candidates").param("page", "0").param("size", "2"))
                .andExpect(status().isServiceUnavailable());
        mockMvc.perform(get("/api/tenants/tenant-1/candidates").param("page", "0").param("size", "2"))
                .andExpect(status().isOk());
    }

    @Test
    void shouldReturnServiceUnavailableForScheduledNumberOfRequests() throws Exception {
        mockMvc.perform(post("/admin/failures")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"mode": "UNAVAILABLE", "count": 2}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.remaining").value(2));

        mockMvc.perform(get(CANDIDATE_1)).andExpect(status().isServiceUnavailable());
        mockMvc.perform(get(CANDIDATE_1)).andExpect(status().isServiceUnavailable());
        mockMvc.perform(get(CANDIDATE_1)).andExpect(status().isOk());
    }

    @Test
    void shouldDelayResponse() throws Exception {
        failureSimulator.schedule(FailureMode.DELAY, 1, Duration.ofMillis(100));

        long start = System.nanoTime();
        mockMvc.perform(get(CANDIDATE_1)).andExpect(status().isOk());
        long elapsedMillis = Duration.ofNanos(System.nanoTime() - start).toMillis();

        assertTrue(elapsedMillis >= 100, "expected delay, was " + elapsedMillis + " ms");
    }

    @Test
    void shouldResetScheduledFailures() throws Exception {
        failureSimulator.schedule(FailureMode.UNAVAILABLE, 5, Duration.ZERO);

        mockMvc.perform(delete("/admin/failures")).andExpect(status().isNoContent());

        mockMvc.perform(get(CANDIDATE_1)).andExpect(status().isOk());
        mockMvc.perform(get("/admin/failures")).andExpect(status().isNotFound());
    }

    @Test
    void shouldRejectInvalidFailureRequest() throws Exception {
        mockMvc.perform(post("/admin/failures")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"mode": "UNAVAILABLE", "count": 0}
                                """))
                .andExpect(status().isBadRequest());
    }
}
