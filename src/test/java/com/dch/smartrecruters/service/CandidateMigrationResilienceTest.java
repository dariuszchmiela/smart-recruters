package com.dch.smartrecruters.service;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import com.dch.smartrecruters.client.ExternalCallExecutor;
import com.dch.smartrecruters.client.ExternalSystemException;
import com.dch.smartrecruters.client.FailureType;
import com.dch.smartrecruters.client.sap.RestSapClient;
import com.dch.smartrecruters.client.smartrecruiters.RestSmartRecruitersClient;
import com.dch.smartrecruters.mapper.CandidateMapper;
import com.dch.smartrecruters.state.MigrationRecordRepository;
import com.dch.smartrecruters.validation.CandidateValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Real HTTP adapters + retry against mocked HTTP servers, mocked migration state.
 */
class CandidateMigrationResilienceTest {

    private static final String SAP_URL = "http://sap.test/api/tenants/tenant-1/candidates/candidate-1";
    private static final String SR_URL = "http://sr.test/api/tenants/tenant-1/candidates";

    private MockRestServiceServer sap;
    private MockRestServiceServer smartRecruiters;
    private MigrationRecordRepository migrationRecordRepository;
    private CandidateMigrationService service;

    @BeforeEach
    void setUp() {
        ExternalCallExecutor executor =
                new ExternalCallExecutor(CircuitBreaker.ofDefaults("test"), 3, Duration.ofMillis(1), 2.0, Duration.ofMillis(5));

        RestClient.Builder sapBuilder = RestClient.builder().baseUrl("http://sap.test");
        sap = MockRestServiceServer.bindTo(sapBuilder).build();

        RestClient.Builder srBuilder = RestClient.builder().baseUrl("http://sr.test");
        smartRecruiters = MockRestServiceServer.bindTo(srBuilder).build();

        migrationRecordRepository = mock(MigrationRecordRepository.class);
        when(migrationRecordRepository.tryStart(eq("tenant-1"), eq("candidate-1"), any())).thenReturn(true);
        when(migrationRecordRepository.markCompleted(anyString(), anyString(), any())).thenReturn(true);
        when(migrationRecordRepository.markFailed(anyString(), anyString(), any())).thenReturn(true);

        service = new CandidateMigrationService(
                new RestSapClient(sapBuilder.build(), executor),
                new CandidateMapper(),
                new CandidateValidator(),
                new RestSmartRecruitersClient(srBuilder.build(), executor),
                migrationRecordRepository
        );
    }

    @Test
    void shouldCompleteMigrationWhenTargetRecoversWithinRetries() {
        sap.expect(once(), requestTo(SAP_URL))
                .andRespond(withSuccess(sapCandidate(), MediaType.APPLICATION_JSON));
        smartRecruiters.expect(times(2), requestTo(SR_URL))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        smartRecruiters.expect(once(), requestTo(SR_URL))
                .andRespond(withStatus(HttpStatus.CREATED));

        service.migrateCandidate("tenant-1", "candidate-1");

        // target retry does not fetch SAP again
        sap.verify();
        smartRecruiters.verify();
        verify(migrationRecordRepository).markCompleted(eq("tenant-1"), eq("candidate-1"), any());
        verify(migrationRecordRepository, never()).markFailed(eq("tenant-1"), eq("candidate-1"), any());
    }

    @Test
    void shouldMarkFailedOnlyAfterRetriesAreExhausted() {
        sap.expect(once(), requestTo(SAP_URL))
                .andRespond(withSuccess(sapCandidate(), MediaType.APPLICATION_JSON));
        smartRecruiters.expect(times(3), requestTo(SR_URL))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        ExternalSystemException exception = assertThrows(
                ExternalSystemException.class,
                () -> service.migrateCandidate("tenant-1", "candidate-1")
        );

        assertEquals(FailureType.TRANSIENT, exception.failureType());
        sap.verify();
        smartRecruiters.verify();
        verify(migrationRecordRepository).markFailed(eq("tenant-1"), eq("candidate-1"), any());
        verify(migrationRecordRepository, never()).markCompleted(eq("tenant-1"), eq("candidate-1"), any());
    }

    @Test
    void shouldFailImmediatelyOnPermanentSourceError() {
        sap.expect(once(), requestTo(SAP_URL))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));
        smartRecruiters.expect(ExpectedCount.never(), requestTo(SR_URL));

        ExternalSystemException exception = assertThrows(
                ExternalSystemException.class,
                () -> service.migrateCandidate("tenant-1", "candidate-1")
        );

        assertEquals(FailureType.PERMANENT, exception.failureType());
        sap.verify();
        smartRecruiters.verify();
        verify(migrationRecordRepository).markFailed(eq("tenant-1"), eq("candidate-1"), any());
    }

    @Test
    void shouldMarkFailedWithoutHttpWhenSourceCircuitBreakerIsOpen() {
        CircuitBreaker openSapBreaker = CircuitBreaker.ofDefaults("sap");
        openSapBreaker.transitionToOpenState();

        RestClient.Builder sapBuilder = RestClient.builder().baseUrl("http://sap.test");
        MockRestServiceServer sapServer = MockRestServiceServer.bindTo(sapBuilder).build();
        sapServer.expect(ExpectedCount.never(), requestTo(SAP_URL));
        smartRecruiters.expect(ExpectedCount.never(), requestTo(SR_URL));

        CandidateMigrationService serviceWithOpenSap = new CandidateMigrationService(
                new RestSapClient(
                        sapBuilder.build(),
                        new ExternalCallExecutor(openSapBreaker, 3, Duration.ofMillis(1), 2.0, Duration.ofMillis(5))
                ),
                new CandidateMapper(),
                new CandidateValidator(),
                new RestSmartRecruitersClient(
                        RestClient.builder().baseUrl("http://sr.test").build(),
                        new ExternalCallExecutor(CircuitBreaker.ofDefaults("sr"), 3, Duration.ofMillis(1), 2.0, Duration.ofMillis(5))
                ),
                migrationRecordRepository
        );

        ExternalSystemException exception = assertThrows(
                ExternalSystemException.class,
                () -> serviceWithOpenSap.migrateCandidate("tenant-1", "candidate-1")
        );

        assertEquals(FailureType.TRANSIENT, exception.failureType());
        sapServer.verify();
        verify(migrationRecordRepository).markFailed(eq("tenant-1"), eq("candidate-1"), any());
        verify(migrationRecordRepository, never()).markCompleted(eq("tenant-1"), eq("candidate-1"), any());
    }

    private String sapCandidate() {
        return """
                {
                  "id": "candidate-1",
                  "tenantId": "tenant-1",
                  "firstName": "John",
                  "lastName": "Smith",
                  "email": "john@example.com"
                }
                """;
    }
}
