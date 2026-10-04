package com.dch.smartrecruters.client;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.dch.smartrecruters.client.sap.RestSapClient;
import com.dch.smartrecruters.client.smartrecruiters.RestSmartRecruitersClient;
import com.dch.smartrecruters.client.smartrecruiters.SmartRecruitersCandidateRequest;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.ExpectedCount.times;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Upstream responses may echo candidate data. Nothing of a remote response body (or of a raw
 * transport exception message) may reach the application exception, its cause chain or the logs.
 */
class ExternalErrorSanitizationTest {

    static final String SENTINEL_EMAIL = "secret-person@example.com";
    static final String SENTINEL_NAME = "SecretFirstName";
    static final String SENTINEL_BODY = """
            {"email": "%s", "firstName": "%s", "detail": "candidate rejected"}
            """.formatted(SENTINEL_EMAIL, SENTINEL_NAME);

    private static final String SAP_URL = "http://sap.test/api/tenants/tenant-1/candidates/candidate-1";
    private static final String SR_URL = "http://sr.test/api/tenants/tenant-1/candidates/candidate-1";

    private MockRestServiceServer sap;
    private MockRestServiceServer smartRecruiters;
    private RestSapClient sapClient;
    private RestSmartRecruitersClient smartRecruitersClient;
    private ListAppender<ILoggingEvent> logs;

    @BeforeEach
    void setUp() {
        RestClient.Builder sapBuilder = RestClient.builder().baseUrl("http://sap.test");
        sap = MockRestServiceServer.bindTo(sapBuilder).build();
        sapClient = new RestSapClient(sapBuilder.build(), executor());

        RestClient.Builder srBuilder = RestClient.builder().baseUrl("http://sr.test");
        smartRecruiters = MockRestServiceServer.bindTo(srBuilder).build();
        smartRecruitersClient = new RestSmartRecruitersClient(srBuilder.build(), executor());

        logs = new ListAppender<>();
        logs.start();
        executorLogger().addAppender(logs);
    }

    @AfterEach
    void detachLogs() {
        executorLogger().detachAppender(logs);
    }

    @Test
    void shouldNotLeakBodyOfPermanentErrorResponse() {
        smartRecruiters.expect(once(), requestTo(SR_URL))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_JSON).body(SENTINEL_BODY));

        ExternalSystemException exception = assertThrows(ExternalSystemException.class, this::upsert);

        assertEquals("SmartRecruiters PUT candidate tenant-1:candidate-1 failed (PERMANENT, HTTP 400)", exception.getMessage());
        assertEquals(FailureType.PERMANENT, exception.failureType());
        assertEquals(ExternalSystemException.Kind.HTTP_RESPONSE, exception.kind());
        assertEquals(OptionalInt.of(400), exception.httpStatus());
        assertNoSentinel(exception);
        // permanent: not retried
        smartRecruiters.verify();
    }

    @Test
    void shouldNotLeakBodyOfTransientErrorResponseAndStillRetry() {
        sap.expect(times(3), requestTo(SAP_URL))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE).contentType(MediaType.APPLICATION_JSON).body(SENTINEL_BODY));

        ExternalSystemException exception = assertThrows(
                ExternalSystemException.class, () -> sapClient.getCandidate("tenant-1", "candidate-1")
        );

        assertEquals("SAP GET candidate tenant-1:candidate-1 failed (TRANSIENT, HTTP 503)", exception.getMessage());
        assertTrue(exception.isTransient());
        assertEquals(OptionalInt.of(503), exception.httpStatus());
        assertNoSentinel(exception);
        // transient: retried up to max attempts, every attempt logged safely
        sap.verify();
        assertEquals(3, logs.list.size());
    }

    @Test
    void shouldNotLeakBodyOfUnreadableSuccessfulResponse() {
        // a 200 whose body cannot be mapped; parser messages quote the offending content
        sap.expect(once(), requestTo(SAP_URL))
                .andRespond(withSuccess("{\"id\": \"candidate-1\", \"email\": \"" + SENTINEL_EMAIL + "\", " + SENTINEL_NAME,
                        MediaType.APPLICATION_JSON));

        ExternalSystemException exception = assertThrows(
                ExternalSystemException.class, () -> sapClient.getCandidate("tenant-1", "candidate-1")
        );

        assertEquals("SAP GET candidate tenant-1:candidate-1 failed (PERMANENT, invalid response)", exception.getMessage());
        assertEquals(ExternalSystemException.Kind.INVALID_RESPONSE, exception.kind());
        assertNoSentinel(exception);
        sap.verify();
    }

    @Test
    void shouldRepresentTimeoutGenericallyWithoutRawMessage() {
        sap.expect(ExpectedCount.times(3), requestTo(SAP_URL))
                .andRespond(withException(new SocketTimeoutException("timed out reading " + SENTINEL_EMAIL)));

        ExternalSystemException exception = assertThrows(
                ExternalSystemException.class, () -> sapClient.getCandidate("tenant-1", "candidate-1")
        );

        assertEquals("SAP GET candidate tenant-1:candidate-1 failed (TRANSIENT, timeout)", exception.getMessage());
        assertEquals(ExternalSystemException.Kind.TIMEOUT, exception.kind());
        assertEquals(OptionalInt.empty(), exception.httpStatus());
        assertNoSentinel(exception);
        sap.verify();
    }

    @Test
    void shouldKeepCircuitBreakerRejectionTransientAndWithoutHttp() {
        CircuitBreaker open = CircuitBreaker.ofDefaults("sap");
        open.transitionToOpenState();
        RestClient.Builder builder = RestClient.builder().baseUrl("http://sap.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(ExpectedCount.never(), requestTo(SAP_URL));
        RestSapClient client = new RestSapClient(builder.build(),
                new ExternalCallExecutor(open, 3, Duration.ofMillis(1), 2.0, Duration.ofMillis(5)));

        ExternalSystemException exception = assertThrows(
                ExternalSystemException.class, () -> client.getCandidate("tenant-1", "candidate-1")
        );

        assertEquals("SAP GET candidate tenant-1:candidate-1 failed (TRANSIENT, circuit breaker open)", exception.getMessage());
        assertEquals(ExternalSystemException.Kind.CIRCUIT_OPEN, exception.kind());
        server.verify();
    }

    @Test
    void shouldNotKeepRawHttpExceptionInCauseChain() {
        smartRecruiters.expect(once(), requestTo(SR_URL))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST).body(SENTINEL_BODY));

        ExternalSystemException exception = assertThrows(ExternalSystemException.class, this::upsert);

        assertNull(exception.getCause());
        assertEquals(0, exception.getSuppressed().length);
    }

    private void upsert() {
        smartRecruitersClient.upsertCandidate("tenant-1",
                new SmartRecruitersCandidateRequest("candidate-1", "John", "Smith", "john@example.com"));
    }

    private void assertNoSentinel(Throwable exception) {
        StringWriter trace = new StringWriter();
        exception.printStackTrace(new PrintWriter(trace));
        assertSafe(trace.toString(), "stack trace / cause chain");
        assertSafe(String.valueOf(exception.getMessage()), "message");
        for (ILoggingEvent event : logs.list) {
            assertSafe(event.getFormattedMessage(), "log message");
            assertNull(event.getThrowableProxy(), "executor logs no throwable");
        }
        assertFalse(logs.list.isEmpty(), "failure was logged");
    }

    static void assertSafe(String text, String where) {
        assertFalse(text.contains(SENTINEL_EMAIL), () -> "sentinel email in " + where + ": " + text);
        assertFalse(text.contains(SENTINEL_NAME), () -> "sentinel name in " + where + ": " + text);
        assertFalse(text.contains("candidate rejected"), () -> "remote body in " + where + ": " + text);
    }

    private static Logger executorLogger() {
        return (Logger) LoggerFactory.getLogger(ExternalCallExecutor.class);
    }

    private static ExternalCallExecutor executor() {
        return new ExternalCallExecutor(CircuitBreaker.ofDefaults("test"), 3, Duration.ofMillis(1), 2.0, Duration.ofMillis(5));
    }
}
