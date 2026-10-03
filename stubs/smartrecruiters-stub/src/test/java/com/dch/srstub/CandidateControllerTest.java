package com.dch.srstub;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Duration;

import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class CandidateControllerTest {

    private static final String JOHN = """
            {
              "externalId": "candidate-1",
              "firstName": "John",
              "lastName": "Smith",
              "email": "john@example.com"
            }
            """;

    private FailureSimulator failureSimulator;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        failureSimulator = new FailureSimulator();
        mockMvc = MockMvcBuilders
                .standaloneSetup(
                        new CandidateController(new CandidateStore(), failureSimulator),
                        new FailureController(failureSimulator)
                )
                .build();
    }

    @Test
    void shouldCreateCandidateAndReadItBack() throws Exception {
        mockMvc.perform(post("/api/tenants/tenant-1/candidates")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JOHN))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/tenants/tenant-1/candidates/candidate-1"))
                .andExpect(jsonPath("$.externalId").value("candidate-1"))
                .andExpect(jsonPath("$.tenantId").value("tenant-1"));

        mockMvc.perform(get("/api/tenants/tenant-1/candidates/candidate-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.firstName").value("John"))
                .andExpect(jsonPath("$.email").value("john@example.com"));
    }

    @Test
    void shouldNotCreateSecondCandidateForDuplicateExternalId() throws Exception {
        String firstId = createAndReturnId(JOHN);

        mockMvc.perform(post("/api/tenants/tenant-1/candidates")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "externalId": "candidate-1",
                                  "firstName": "Johnny",
                                  "lastName": "Smith",
                                  "email": "johnny@example.com"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(firstId))
                .andExpect(jsonPath("$.firstName").value("John"));

        mockMvc.perform(get("/api/tenants/tenant-1/candidates"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)));
    }

    @Test
    void shouldTreatSameExternalIdInOtherTenantAsDifferentCandidate() throws Exception {
        createAndReturnId(JOHN);

        mockMvc.perform(post("/api/tenants/tenant-2/candidates")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JOHN))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/tenants/tenant-1/candidates"))
                .andExpect(jsonPath("$", hasSize(1)));
        mockMvc.perform(get("/api/tenants/tenant-2/candidates"))
                .andExpect(jsonPath("$", hasSize(1)));
    }

    @Test
    void shouldReturnNotFoundForUnknownCandidate() throws Exception {
        mockMvc.perform(get("/api/tenants/tenant-1/candidates/unknown"))
                .andExpect(status().isNotFound());
    }

    @Test
    void shouldRejectCandidateWithoutExternalId() throws Exception {
        mockMvc.perform(post("/api/tenants/tenant-1/candidates")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"firstName": "John", "lastName": "Smith", "email": "john@example.com"}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void shouldReturnServiceUnavailableWithoutStoringCandidate() throws Exception {
        failureSimulator.schedule(FailureMode.UNAVAILABLE, 1, Duration.ZERO);

        postJohn().andExpect(status().isServiceUnavailable());

        mockMvc.perform(get("/api/tenants/tenant-1/candidates/candidate-1"))
                .andExpect(status().isNotFound());

        postJohn().andExpect(status().isCreated());
    }

    @Test
    void shouldStoreCandidateEvenWhenResponseIsLostAndNotDuplicateOnRetry() throws Exception {
        mockMvc.perform(post("/admin/failures")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"mode": "UNAVAILABLE_AFTER_SAVE", "count": 1}
                                """))
                .andExpect(status().isOk());

        // attempt 1: target stores the candidate but the client sees 503
        postJohn().andExpect(status().isServiceUnavailable());

        mockMvc.perform(get("/api/tenants/tenant-1/candidates/candidate-1"))
                .andExpect(status().isOk());

        // attempt 2 (client retry): same key -> existing candidate, no second record
        postJohn()
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.externalId").value("candidate-1"));

        mockMvc.perform(get("/api/tenants/tenant-1/candidates"))
                .andExpect(jsonPath("$", hasSize(1)));
    }

    @Test
    void shouldStoreCandidateBeforeDelayedResponse() throws Exception {
        failureSimulator.schedule(FailureMode.DELAY_AFTER_SAVE, 1, Duration.ofMillis(100));

        long start = System.nanoTime();
        postJohn().andExpect(status().isCreated());
        long elapsedMillis = Duration.ofNanos(System.nanoTime() - start).toMillis();

        assertTrue(elapsedMillis >= 100, "expected delay, was " + elapsedMillis + " ms");
        postJohn().andExpect(status().isOk());
    }

    private ResultActions postJohn() throws Exception {
        return mockMvc.perform(post("/api/tenants/tenant-1/candidates")
                .contentType(MediaType.APPLICATION_JSON)
                .content(JOHN));
    }

    private String createAndReturnId(String body) throws Exception {
        String response = mockMvc.perform(post("/api/tenants/tenant-1/candidates")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();

        return JsonPath.read(response, "$.id");
    }
}
