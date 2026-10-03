package com.dch.smartrecruters.config;

import com.dch.smartrecruters.client.ExternalSystemException;
import com.dch.smartrecruters.client.FailureType;
import com.dch.smartrecruters.client.sap.RestSapClient;
import com.dch.smartrecruters.client.smartrecruiters.RestSmartRecruitersClient;
import com.dch.smartrecruters.client.smartrecruiters.SmartRecruitersCandidateRequest;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.ExpectedCount.times;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Real circuit breaker configuration from {@link ClientConfiguration} + real HTTP adapters
 * against mocked HTTP servers. Time is controlled by a test clock - no sleeping.
 */
class ClientCircuitBreakerTest {

    private static final int RETRY_ATTEMPTS = 3;
    private static final int SLIDING_WINDOW = 10;
    private static final int MINIMUM_CALLS = 4;
    private static final Duration WAIT_IN_OPEN = Duration.ofSeconds(30);

    private static final String SAP_URL = "http://sap.test/api/tenants/tenant-1/candidates/candidate-1";
    private static final String SR_URL = "http://sr.test/api/tenants/tenant-1/candidates";

    private static final String CANDIDATE_JSON = """
            {"id": "candidate-1", "tenantId": "tenant-1", "firstName": "John",
             "lastName": "Smith", "email": "john@example.com"}
            """;

    private final MutableClock clock = new MutableClock(Instant.parse("2026-01-01T10:00:00Z"));

    private CircuitBreaker sapBreaker;
    private CircuitBreaker srBreaker;
    private MockRestServiceServer sap;
    private MockRestServiceServer smartRecruiters;
    private RestSapClient sapClient;
    private RestSmartRecruitersClient smartRecruitersClient;

    @BeforeEach
    void setUp() {
        ClientProperties.CircuitBreakerSettings settings = new ClientProperties.CircuitBreakerSettings(
                SLIDING_WINDOW, MINIMUM_CALLS, 50, WAIT_IN_OPEN, 2
        );
        ClientProperties.Retry retry =
                new ClientProperties.Retry(RETRY_ATTEMPTS, Duration.ofMillis(1), 2.0, Duration.ofMillis(5));
        CircuitBreakerRegistry registry =
                CircuitBreakerRegistry.of(ClientConfiguration.circuitBreakerConfig(settings, clock));

        sapBreaker = registry.circuitBreaker(ClientConfiguration.SAP);
        srBreaker = registry.circuitBreaker(ClientConfiguration.SMARTRECRUITERS);

        RestClient.Builder sapBuilder = RestClient.builder().baseUrl("http://sap.test");
        sap = MockRestServiceServer.bindTo(sapBuilder).build();
        sapClient = new RestSapClient(sapBuilder.build(), ClientConfiguration.callExecutor(sapBreaker, retry));

        RestClient.Builder srBuilder = RestClient.builder().baseUrl("http://sr.test");
        smartRecruiters = MockRestServiceServer.bindTo(srBuilder).build();
        smartRecruitersClient = new RestSmartRecruitersClient(
                srBuilder.build(), ClientConfiguration.callExecutor(srBreaker, retry)
        );
    }

    @Test
    void shouldStayClosedOnSuccessfulCalls() {
        sap.expect(times(6), requestTo(SAP_URL))
                .andRespond(withSuccess(CANDIDATE_JSON, MediaType.APPLICATION_JSON));

        for (int i = 0; i < 6; i++) {
            sapClient.getCandidate("tenant-1", "candidate-1");
        }

        assertEquals(CircuitBreaker.State.CLOSED, sapBreaker.getState());
        assertEquals(6, sapBreaker.getMetrics().getNumberOfSuccessfulCalls());
        sap.verify();
    }

    @Test
    void shouldCountOneFailurePerExhaustedCallNotPerRetryAttempt() {
        sap.expect(times(RETRY_ATTEMPTS), requestTo(SAP_URL))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        assertThrows(ExternalSystemException.class, () -> sapClient.getCandidate("tenant-1", "candidate-1"));

        assertEquals(1, sapBreaker.getMetrics().getNumberOfFailedCalls());
        assertEquals(CircuitBreaker.State.CLOSED, sapBreaker.getState());
        sap.verify();
    }

    @Test
    void shouldOpenAfterRepeatedTransientFailuresAndThenFailFastWithoutHttp() {
        openSapBreaker();

        ExternalSystemException exception = assertThrows(
                ExternalSystemException.class,
                () -> sapClient.getCandidate("tenant-1", "candidate-1")
        );

        assertEquals(FailureType.TRANSIENT, exception.failureType());
        assertInstanceOf(CallNotPermittedException.class, exception.getCause());
        assertEquals(1, sapBreaker.getMetrics().getNumberOfNotPermittedCalls());
        // exactly MINIMUM_CALLS * RETRY_ATTEMPTS requests, none for the rejected call
        sap.verify();
    }

    @Test
    void shouldIgnorePermanentFailures() {
        sap.expect(times(6), requestTo(SAP_URL))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));

        for (int i = 0; i < 6; i++) {
            ExternalSystemException exception = assertThrows(
                    ExternalSystemException.class,
                    () -> sapClient.getCandidate("tenant-1", "candidate-1")
            );
            assertEquals(FailureType.PERMANENT, exception.failureType());
        }

        assertEquals(CircuitBreaker.State.CLOSED, sapBreaker.getState());
        assertEquals(0, sapBreaker.getMetrics().getNumberOfFailedCalls());
        // not recorded as failure = upstream responded, Resilience4j counts it as a successful call
        assertEquals(6, sapBreaker.getMetrics().getNumberOfSuccessfulCalls());
        sap.verify();
    }

    @Test
    void shouldKeepSmartRecruitersClosedWhenSapIsOpen() {
        openSapBreaker();
        smartRecruiters.expect(once(), requestTo(SR_URL))
                .andRespond(withStatus(HttpStatus.CREATED));

        smartRecruitersClient.createCandidate(
                "tenant-1",
                new SmartRecruitersCandidateRequest("candidate-1", "John", "Smith", "john@example.com")
        );

        assertEquals(CircuitBreaker.State.OPEN, sapBreaker.getState());
        assertEquals(CircuitBreaker.State.CLOSED, srBreaker.getState());
        smartRecruiters.verify();
    }

    @Test
    void shouldCloseAfterSuccessfulCallsInHalfOpenState() {
        openSapBreaker();
        sap.reset();
        sap.expect(times(2), requestTo(SAP_URL))
                .andRespond(withSuccess(CANDIDATE_JSON, MediaType.APPLICATION_JSON));

        // still OPEN just before the wait duration elapses
        clock.advance(WAIT_IN_OPEN.minusSeconds(1));
        assertThrows(ExternalSystemException.class, () -> sapClient.getCandidate("tenant-1", "candidate-1"));
        assertEquals(CircuitBreaker.State.OPEN, sapBreaker.getState());

        clock.advance(Duration.ofSeconds(2));

        sapClient.getCandidate("tenant-1", "candidate-1");
        assertEquals(CircuitBreaker.State.HALF_OPEN, sapBreaker.getState());

        sapClient.getCandidate("tenant-1", "candidate-1");
        assertEquals(CircuitBreaker.State.CLOSED, sapBreaker.getState());
        sap.verify();
    }

    @Test
    void shouldReopenWhenTrialCallsFailInHalfOpenState() {
        openSapBreaker();
        sap.reset();
        sap.expect(times(2 * RETRY_ATTEMPTS), requestTo(SAP_URL))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        clock.advance(WAIT_IN_OPEN.plusSeconds(1));

        assertThrows(ExternalSystemException.class, () -> sapClient.getCandidate("tenant-1", "candidate-1"));
        assertEquals(CircuitBreaker.State.HALF_OPEN, sapBreaker.getState());

        assertThrows(ExternalSystemException.class, () -> sapClient.getCandidate("tenant-1", "candidate-1"));
        assertEquals(CircuitBreaker.State.OPEN, sapBreaker.getState());
        sap.verify();
    }

    private void openSapBreaker() {
        sap.expect(ExpectedCount.times(MINIMUM_CALLS * RETRY_ATTEMPTS), requestTo(SAP_URL))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        for (int i = 0; i < MINIMUM_CALLS; i++) {
            assertEquals(CircuitBreaker.State.CLOSED, sapBreaker.getState());
            assertThrows(ExternalSystemException.class, () -> sapClient.getCandidate("tenant-1", "candidate-1"));
        }

        assertEquals(CircuitBreaker.State.OPEN, sapBreaker.getState());
        assertEquals(MINIMUM_CALLS, sapBreaker.getMetrics().getNumberOfFailedCalls());
    }

    private static final class MutableClock extends Clock {

        private Instant now;

        private MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
