package com.dch.sapstub;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class SapCandidateControllerTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .standaloneSetup(new SapCandidateController(new SapCandidateStore()))
                .build();
    }

    @Test
    void shouldReturnExistingCandidate() throws Exception {
        mockMvc.perform(get("/api/tenants/tenant-1/candidates/candidate-1"))
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
}
