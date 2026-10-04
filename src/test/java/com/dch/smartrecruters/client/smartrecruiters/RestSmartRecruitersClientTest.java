package com.dch.smartrecruters.client.smartrecruiters;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import com.dch.smartrecruters.client.ExternalCallExecutor;
import com.dch.smartrecruters.client.ExternalSystemException;
import com.dch.smartrecruters.client.FailureType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.ExpectedCount.times;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class RestSmartRecruitersClientTest {

    private static final String URL = "http://sr.test/api/tenants/tenant-1/candidates";

    private static final SmartRecruitersCandidateRequest REQUEST =
            new SmartRecruitersCandidateRequest("candidate-1", "John", "Smith", "john@example.com");

    private MockRestServiceServer server;
    private RestSmartRecruitersClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://sr.test");
        server = MockRestServiceServer.bindTo(builder).build();
        client = new RestSmartRecruitersClient(
                builder.build(),
                new ExternalCallExecutor(CircuitBreaker.ofDefaults("test"), 3, Duration.ofMillis(1), 2.0, Duration.ofMillis(5))
        );
    }

    @Test
    void shouldPostCandidateToTenantEndpoint() {
        server.expect(requestTo(URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(content().json("""
                        {
                          "externalId": "candidate-1",
                          "firstName": "John",
                          "lastName": "Smith",
                          "email": "john@example.com"
                        }
                        """))
                .andRespond(withStatus(HttpStatus.CREATED));

        client.createCandidate("tenant-1", REQUEST);

        server.verify();
    }

    @Test
    void shouldRetrySamePostAfterTransientErrors() {
        server.expect(times(2), requestTo(URL))
                .andExpect(content().json("{\"externalId\": \"candidate-1\"}"))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        // target already has the candidate from a "lost" attempt -> idempotent 200
        server.expect(once(), requestTo(URL))
                .andExpect(content().json("{\"externalId\": \"candidate-1\"}"))
                .andRespond(withStatus(HttpStatus.OK));

        client.createCandidate("tenant-1", REQUEST);

        server.verify();
    }

    @Test
    void shouldPutCandidateToItsBusinessIdentityForUpsert() {
        server.expect(requestTo(URL + "/candidate-1"))
                .andExpect(method(HttpMethod.PUT))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(content().json("""
                        {
                          "externalId": "candidate-1",
                          "firstName": "John",
                          "lastName": "Smith",
                          "email": "john@example.com"
                        }
                        """))
                .andRespond(withStatus(HttpStatus.OK));

        client.upsertCandidate("tenant-1", REQUEST);

        server.verify();
    }

    @Test
    void shouldRetryUpsertAfterTransientErrors() {
        server.expect(times(2), requestTo(URL + "/candidate-1"))
                .andExpect(method(HttpMethod.PUT))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        server.expect(once(), requestTo(URL + "/candidate-1"))
                .andExpect(method(HttpMethod.PUT))
                .andRespond(withStatus(HttpStatus.OK));

        client.upsertCandidate("tenant-1", REQUEST);

        server.verify();
    }

    @Test
    void shouldNotRetryRejectedUpsert() {
        server.expect(once(), requestTo(URL + "/candidate-1"))
                .andRespond(withStatus(HttpStatus.CONFLICT));

        ExternalSystemException exception = assertThrows(
                ExternalSystemException.class,
                () -> client.upsertCandidate("tenant-1", REQUEST)
        );

        assertEquals(FailureType.PERMANENT, exception.failureType());
        server.verify();
    }

    @Test
    void shouldReadCandidatePageIgnoringTargetGeneratedFields() {
        server.expect(requestTo(URL + "?page=1&size=2"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {
                          "items": [
                            {"id": "sr-1", "tenantId": "tenant-1", "externalId": "candidate-1", "firstName": "John",
                             "lastName": "Smith", "email": "john@example.com",
                             "createdAt": "2026-01-01T10:00:00Z", "updatedAt": "2026-01-01T10:00:00Z"}
                          ],
                          "page": 1,
                          "size": 2,
                          "hasNext": false
                        }
                        """, MediaType.APPLICATION_JSON));

        SmartRecruitersCandidatePage page = client.getCandidates("tenant-1", 1, 2);

        assertEquals(new SmartRecruitersCandidatePage(
                List.of(new SmartRecruitersCandidate("candidate-1", "John", "Smith", "john@example.com")), 1, 2, false
        ), page);
        server.verify();
    }

    @Test
    void shouldRetryCandidatePageAfterTransientError() {
        server.expect(once(), requestTo(URL + "?page=0&size=10"))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        server.expect(once(), requestTo(URL + "?page=0&size=10"))
                .andRespond(withSuccess("""
                        {"items": [], "page": 0, "size": 10, "hasNext": false}
                        """, MediaType.APPLICATION_JSON));

        assertEquals(List.of(), client.getCandidates("tenant-1", 0, 10).items());
        server.verify();
    }

    @Test
    void shouldNotRetryBadRequest() {
        server.expect(once(), requestTo(URL))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST));

        ExternalSystemException exception = assertThrows(
                ExternalSystemException.class,
                () -> client.createCandidate("tenant-1", REQUEST)
        );

        assertEquals(FailureType.PERMANENT, exception.failureType());
        server.verify();
    }
}
