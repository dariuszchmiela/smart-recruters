package com.dch.smartrecruters.client.sap;

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

import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.ExpectedCount.times;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class RestSapClientTest {

    private static final String URL = "http://sap.test/api/tenants/tenant-1/candidates/candidate-1";

    private static final String CANDIDATE_JSON = """
            {
              "id": "candidate-1",
              "tenantId": "tenant-1",
              "firstName": "John",
              "lastName": "Smith",
              "email": "john@example.com"
            }
            """;

    private MockRestServiceServer server;
    private RestSapClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://sap.test");
        server = MockRestServiceServer.bindTo(builder).build();
        client = new RestSapClient(
                builder.build(),
                new ExternalCallExecutor(CircuitBreaker.ofDefaults("test"), 3, Duration.ofMillis(1), 2.0, Duration.ofMillis(5))
        );
    }

    @Test
    void shouldGetCandidateFromSap() {
        server.expect(requestTo(URL))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(CANDIDATE_JSON, MediaType.APPLICATION_JSON));

        SapCandidate candidate = client.getCandidate("tenant-1", "candidate-1");

        assertEquals(
                new SapCandidate("candidate-1", "tenant-1", "John", "Smith", "john@example.com"),
                candidate
        );
        server.verify();
    }

    @Test
    void shouldGetCandidatePageFromSap() {
        server.expect(requestTo("http://sap.test/api/tenants/tenant-1/candidates?page=2&size=50"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {
                          "items": [%s],
                          "page": 2,
                          "size": 50,
                          "hasNext": true
                        }
                        """.formatted(CANDIDATE_JSON), MediaType.APPLICATION_JSON));

        SapCandidatePage page = client.getCandidates("tenant-1", 2, 50);

        assertEquals(
                new SapCandidatePage(
                        List.of(new SapCandidate("candidate-1", "tenant-1", "John", "Smith", "john@example.com")),
                        2,
                        50,
                        true
                ),
                page
        );
        assertEquals(3, page.nextPage());
        server.verify();
    }

    @Test
    void shouldRetryTransientErrorsWhenReadingPage() {
        String pageUrl = "http://sap.test/api/tenants/tenant-1/candidates?page=0&size=10";
        server.expect(once(), requestTo(pageUrl))
                .andRespond(withStatus(HttpStatus.BAD_GATEWAY));
        server.expect(once(), requestTo(pageUrl))
                .andRespond(withSuccess("""
                        {"items": [], "page": 0, "size": 10, "hasNext": false}
                        """, MediaType.APPLICATION_JSON));

        SapCandidatePage page = client.getCandidates("tenant-1", 0, 10);

        assertEquals(List.of(), page.items());
        assertFalse(page.hasNext());
        server.verify();
    }

    @Test
    void shouldRetryTransientErrorsAndSucceed() {
        server.expect(times(2), requestTo(URL))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        server.expect(once(), requestTo(URL))
                .andRespond(withSuccess(CANDIDATE_JSON, MediaType.APPLICATION_JSON));

        SapCandidate candidate = client.getCandidate("tenant-1", "candidate-1");

        assertEquals("candidate-1", candidate.id());
        server.verify();
    }

    @Test
    void shouldRetryTimeout() {
        server.expect(once(), requestTo(URL))
                .andRespond(withException(new SocketTimeoutException("Read timed out")));
        server.expect(once(), requestTo(URL))
                .andRespond(withSuccess(CANDIDATE_JSON, MediaType.APPLICATION_JSON));

        client.getCandidate("tenant-1", "candidate-1");

        server.verify();
    }

    @Test
    void shouldNotRetryPermanentError() {
        server.expect(once(), requestTo(URL))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));

        ExternalSystemException exception = assertThrows(
                ExternalSystemException.class,
                () -> client.getCandidate("tenant-1", "candidate-1")
        );

        assertEquals(FailureType.PERMANENT, exception.failureType());
        server.verify();
    }

    @Test
    void shouldFailWithTransientErrorWhenRetriesAreExhausted() {
        server.expect(times(3), requestTo(URL))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        ExternalSystemException exception = assertThrows(
                ExternalSystemException.class,
                () -> client.getCandidate("tenant-1", "candidate-1")
        );

        assertEquals(FailureType.TRANSIENT, exception.failureType());
        server.verify();
    }
}
