package com.dch.smartrecruters.service;

import com.dch.smartrecruters.client.ExternalCallExecutor;
import com.dch.smartrecruters.client.ExternalSystemException;
import com.dch.smartrecruters.client.FailureType;
import com.dch.smartrecruters.client.sap.RestSapClient;
import com.dch.smartrecruters.client.smartrecruiters.RestSmartRecruitersClient;
import com.dch.smartrecruters.mapper.CandidateMapper;
import com.dch.smartrecruters.messaging.CandidateChangedEvent;
import com.dch.smartrecruters.state.CandidateDeltaEventRepository;
import com.dch.smartrecruters.validation.CandidateValidator;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.ExpectedCount.times;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * The delta path goes through the same HTTP adapters as the initial load, so HTTP retry
 * (inside one Kafka delivery) and the circuit breaker apply without any delta-specific code.
 */
class CandidateDeltaResilienceTest {

    private static final String SAP_URL = "http://sap.test/api/tenants/tenant-1/candidates/candidate-1";
    private static final String SR_URL = "http://sr.test/api/tenants/tenant-1/candidates/candidate-1";
    private static final String CANDIDATE_JSON = """
            {"id": "candidate-1", "tenantId": "tenant-1", "firstName": "John",
             "lastName": "Smith", "email": "john@example.com"}
            """;

    private MockRestServiceServer sap;
    private MockRestServiceServer smartRecruiters;
    private CandidateDeltaEventRepository events;
    private CircuitBreaker targetBreaker;
    private CandidateDeltaService service;
    private CandidateChangedEvent event;

    @BeforeEach
    void setUp() {
        RestClient.Builder sapBuilder = RestClient.builder().baseUrl("http://sap.test");
        sap = MockRestServiceServer.bindTo(sapBuilder).build();
        RestClient.Builder srBuilder = RestClient.builder().baseUrl("http://sr.test");
        smartRecruiters = MockRestServiceServer.bindTo(srBuilder).build();
        targetBreaker = CircuitBreaker.ofDefaults("smartrecruiters");

        events = mock(CandidateDeltaEventRepository.class);
        event = new CandidateChangedEvent(UUID.randomUUID(), "tenant-1", "candidate-1", Instant.now());
        when(events.tryClaim(eq(event.eventId()), eq("tenant-1"), eq("candidate-1"), any(), any())).thenReturn(true);
        when(events.markCompleted(any(), any())).thenReturn(true);
        when(events.markFailed(any(), any(), anyString())).thenReturn(true);

        service = new CandidateDeltaService(
                new RestSapClient(sapBuilder.build(), executor(CircuitBreaker.ofDefaults("sap"))),
                new CandidateMapper(),
                new CandidateValidator(),
                new RestSmartRecruitersClient(srBuilder.build(), executor(targetBreaker)),
                events
        );
    }

    @Test
    void shouldRecoverWithinOneDeliveryThroughHttpRetry() {
        sap.expect(once(), requestTo(SAP_URL))
                .andRespond(withSuccess(CANDIDATE_JSON, MediaType.APPLICATION_JSON));
        smartRecruiters.expect(times(2), requestTo(SR_URL))
                .andExpect(method(HttpMethod.PUT))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        smartRecruiters.expect(once(), requestTo(SR_URL))
                .andExpect(method(HttpMethod.PUT))
                .andRespond(withStatus(HttpStatus.OK));

        assertEquals(DeltaEventOutcome.PROCESSED, service.process(event));

        sap.verify();
        smartRecruiters.verify();
        // one fencing token for the whole attempt, HTTP retries included
        ArgumentCaptor<UUID> claimedBy = ArgumentCaptor.forClass(UUID.class);
        verify(events).tryClaim(eq(event.eventId()), any(), any(), any(), claimedBy.capture());
        verify(events).markCompleted(event.eventId(), claimedBy.getValue());
        verify(events, never()).markFailed(any(), any(), anyString());
    }

    @Test
    void shouldHandOverToKafkaRetryWhenHttpRetriesAreExhausted() {
        sap.expect(once(), requestTo(SAP_URL))
                .andRespond(withSuccess(CANDIDATE_JSON, MediaType.APPLICATION_JSON));
        smartRecruiters.expect(times(3), requestTo(SR_URL))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        ExternalSystemException thrown = assertThrows(ExternalSystemException.class, () -> service.process(event));

        // transient, not PermanentDeltaEventException -> retryable by the Kafka error handler
        assertEquals(FailureType.TRANSIENT, thrown.failureType());
        smartRecruiters.verify();
        verify(events).markFailed(eq(event.eventId()), any(), anyString());
    }

    @Test
    void shouldFailFastWithoutHttpWhenTargetCircuitBreakerIsOpen() {
        targetBreaker.transitionToOpenState();
        sap.expect(once(), requestTo(SAP_URL))
                .andRespond(withSuccess(CANDIDATE_JSON, MediaType.APPLICATION_JSON));
        smartRecruiters.expect(ExpectedCount.never(), requestTo(SR_URL));

        ExternalSystemException thrown = assertThrows(ExternalSystemException.class, () -> service.process(event));

        assertEquals(FailureType.TRANSIENT, thrown.failureType());
        assertInstanceOf(CallNotPermittedException.class, thrown.getCause());
        smartRecruiters.verify();
        verify(events).markFailed(eq(event.eventId()), any(), anyString());
    }

    @Test
    void shouldNotRetryWhenCandidateNoLongerExistsInSource() {
        sap.expect(once(), requestTo(SAP_URL))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));
        smartRecruiters.expect(ExpectedCount.never(), requestTo(SR_URL));

        assertThrows(PermanentDeltaEventException.class, () -> service.process(event));

        sap.verify();
        verify(events).markFailed(eq(event.eventId()), any(), anyString());
    }

    private static ExternalCallExecutor executor(CircuitBreaker circuitBreaker) {
        return new ExternalCallExecutor(circuitBreaker, 3, Duration.ofMillis(1), 2.0, Duration.ofMillis(5));
    }
}
