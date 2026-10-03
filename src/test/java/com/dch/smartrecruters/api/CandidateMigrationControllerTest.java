package com.dch.smartrecruters.api;

import com.dch.smartrecruters.service.CandidateMigrationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class CandidateMigrationControllerTest {

    private CandidateMigrationService candidateMigrationService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        candidateMigrationService = mock(CandidateMigrationService.class);
        mockMvc = MockMvcBuilders
                .standaloneSetup(new CandidateMigrationController(candidateMigrationService))
                .build();
    }

    @Test
    void shouldTriggerCandidateMigration() throws Exception {
        mockMvc.perform(post("/api/migrations/tenant-1/candidates/candidate-1"))
                .andExpect(status().isNoContent());

        verify(candidateMigrationService).migrateCandidate("tenant-1", "candidate-1");
    }
}
