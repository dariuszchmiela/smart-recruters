package com.dch.smartrecruters.service;

import com.dch.smartrecruters.client.ExternalSystemException;
import com.dch.smartrecruters.client.FailureType;
import com.dch.smartrecruters.client.SapClient;
import com.dch.smartrecruters.client.SmartRecruitersClient;
import com.dch.smartrecruters.client.sap.SapCandidate;
import com.dch.smartrecruters.client.sap.SapCandidatePage;
import com.dch.smartrecruters.client.smartrecruiters.SmartRecruitersCandidate;
import com.dch.smartrecruters.client.smartrecruiters.SmartRecruitersCandidatePage;
import com.dch.smartrecruters.client.smartrecruiters.SmartRecruitersCandidateRequest;
import com.dch.smartrecruters.mapper.CandidateMapper;
import com.dch.smartrecruters.state.ReconciliationItem;
import com.dch.smartrecruters.state.ReconciliationItemPage;
import com.dch.smartrecruters.state.ReconciliationResult;
import com.dch.smartrecruters.state.ReconciliationRun;
import com.dch.smartrecruters.state.ReconciliationRunStatus;
import com.dch.smartrecruters.state.jdbc.JdbcReconciliationRepository;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import com.dch.smartrecruters.client.ExternalCallExecutor;
import com.dch.smartrecruters.client.smartrecruiters.RestSmartRecruitersClient;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.ResourceAccessException;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real PostgreSQL (Testcontainers) and real JDBC repository; SAP and SmartRecruiters are paged
 * in-memory systems whose write operations fail the test, so the run is provably read-only.
 * Skipped when no Docker-compatible runtime is available.
 */
@Testcontainers(disabledWithoutDocker = true)
class CandidateReconciliationServiceIntegrationTest {

    private static final String TENANT = "tenant-1";
    private static final int PAGE_SIZE = 2;

    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17");

    private static HikariDataSource dataSource;

    private FakeSap sap;
    private FakeTarget target;
    private JdbcReconciliationRepository repository;
    private CandidateReconciliationService service;

    @BeforeAll
    static void setUpDatabase() {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(POSTGRES.getJdbcUrl());
        config.setUsername(POSTGRES.getUsername());
        config.setPassword(POSTGRES.getPassword());
        dataSource = new HikariDataSource(config);

        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .load()
                .migrate();
    }

    @AfterAll
    static void closeDataSource() {
        dataSource.close();
    }

    @BeforeEach
    void setUp() throws SQLException {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("TRUNCATE reconciliation_run, candidate_reconciliation_item, candidate_migration");
        }
        sap = new FakeSap();
        target = new FakeTarget();
        repository = new JdbcReconciliationRepository(dataSource, Duration.ofMinutes(10));
        service = new CandidateReconciliationService(sap, target, new CandidateMapper(), repository, PAGE_SIZE);
    }

    @Test
    void shouldMatchIdenticalSourceAndTarget() {
        addToBoth(TENANT, "c1", "John", "Smith", "john@example.com");
        addToBoth(TENANT, "c2", "Anna", "Nowak", "anna@example.com");

        ReconciliationRun run = reconcile(TENANT);

        assertSummary(run, 2, 2, 2, 0, 0, 0);
        assertEquals(List.of(), service.findItems(run.runId(), null, 0, 100).items());
    }

    @Test
    void shouldReportCandidateMissingInTarget() {
        addToBoth(TENANT, "c1", "John", "Smith", "john@example.com");
        sap.add(TENANT, "c2", "Anna", "Nowak", "anna@example.com");

        ReconciliationRun run = reconcile(TENANT);

        assertSummary(run, 2, 1, 1, 1, 0, 0);
        ReconciliationItem missing = only(run, ReconciliationResult.MISSING_IN_TARGET);
        assertEquals("c2", missing.externalId());
        assertNotNull(missing.sourceFingerprint());
        assertNull(missing.targetFingerprint());
    }

    @Test
    void shouldReportUnexpectedCandidateInTarget() {
        addToBoth(TENANT, "c1", "John", "Smith", "john@example.com");
        target.add(TENANT, "c9", "Ghost", "Record", "ghost@example.com");

        ReconciliationRun run = reconcile(TENANT);

        assertSummary(run, 1, 2, 1, 0, 1, 0);
        ReconciliationItem unexpected = only(run, ReconciliationResult.UNEXPECTED_IN_TARGET);
        assertEquals("c9", unexpected.externalId());
        assertNull(unexpected.sourceFingerprint());
        assertNotNull(unexpected.targetFingerprint());
    }

    @Test
    void shouldReportChangedFirstNameAsMismatch() {
        sap.add(TENANT, "c1", "John", "Smith", "john@example.com");
        target.add(TENANT, "c1", "Jon", "Smith", "john@example.com");

        assertSingleMismatch(reconcile(TENANT));
    }

    @Test
    void shouldReportChangedLastNameAsMismatch() {
        sap.add(TENANT, "c1", "John", "Smith", "john@example.com");
        target.add(TENANT, "c1", "John", "Smyth", "john@example.com");

        assertSingleMismatch(reconcile(TENANT));
    }

    @Test
    void shouldReportChangedEmailAsMismatch() {
        sap.add(TENANT, "c1", "John", "Smith", "john.new@example.com");
        target.add(TENANT, "c1", "John", "Smith", "john@example.com");

        assertSingleMismatch(reconcile(TENANT));
    }

    @Test
    void shouldNotTreatWhitespaceOnlyDifferenceAsMismatch() {
        sap.add(TENANT, "c1", " John", "Smith ", "john@example.com");
        target.add(TENANT, "c1", "John", "Smith", "john@example.com");

        assertSummary(reconcile(TENANT), 1, 1, 1, 0, 0, 0);
    }

    @Test
    void shouldReadSourceAndTargetOverMultiplePages() {
        // 7 source candidates (4 pages of 2), 5 target candidates (3 pages of 2)
        for (int i = 1; i <= 7; i++) {
            sap.add(TENANT, "c" + i, "First" + i, "Last" + i, "p" + i + "@example.com");
        }
        for (int i = 1; i <= 5; i++) {
            target.add(TENANT, "c" + i, "First" + i, "Last" + i, "p" + i + "@example.com");
        }
        target.add(TENANT, "c3", "Changed", "Last3", "p3@example.com");

        ReconciliationRun run = reconcile(TENANT);

        assertEquals(List.of(0, 1, 2, 3), sap.requestedPages);
        assertEquals(List.of(0, 1, 2), target.requestedPages);
        assertSummary(run, 7, 5, 4, 2, 0, 1);
        assertEquals(List.of("c6", "c7"), ids(service.findItems(run.runId(), ReconciliationResult.MISSING_IN_TARGET, 0, 100)));
        assertEquals(List.of("c3"), ids(service.findItems(run.runId(), ReconciliationResult.MISMATCHED, 0, 100)));
    }

    @Test
    void shouldKeepTenantsIsolated() {
        sap.add("tenant-1", "c1", "John", "Smith", "john@example.com");
        target.add("tenant-1", "c1", "John", "Smith", "john@example.com");
        // same externalId in tenant-2 with different data, and only in tenant-2's target
        sap.add("tenant-2", "c1", "Maria", "Garcia", "maria@example.com");
        target.add("tenant-2", "c1", "Maria", "Lopez", "maria@example.com");
        target.add("tenant-2", "c2", "Only", "Target", "only@example.com");

        ReconciliationRun first = reconcile("tenant-1");
        ReconciliationRun second = reconcile("tenant-2");

        assertSummary(first, 1, 1, 1, 0, 0, 0);
        assertSummary(second, 1, 2, 0, 0, 1, 1);
        assertEquals(List.of(), service.findItems(first.runId(), null, 0, 100).items());
        assertEquals(List.of("c1", "c2"), ids(service.findItems(second.runId(), null, 0, 100)));
    }

    @Test
    void shouldCompleteForEmptySourceAndTarget() {
        assertSummary(reconcile(TENANT), 0, 0, 0, 0, 0, 0);
    }

    @Test
    void shouldReportEverythingUnexpectedWhenSourceIsEmpty() {
        target.add(TENANT, "c1", "John", "Smith", "john@example.com");
        target.add(TENANT, "c2", "Anna", "Nowak", "anna@example.com");
        target.add(TENANT, "c3", "Tom", "Brown", "tom@example.com");

        assertSummary(reconcile(TENANT), 0, 3, 0, 0, 3, 0);
    }

    @Test
    void shouldReportEverythingMissingWhenTargetIsEmpty() {
        sap.add(TENANT, "c1", "John", "Smith", "john@example.com");
        sap.add(TENANT, "c2", "Anna", "Nowak", "anna@example.com");
        sap.add(TENANT, "c3", "Tom", "Brown", "tom@example.com");

        assertSummary(reconcile(TENANT), 3, 0, 0, 3, 0, 0);
    }

    @Test
    void shouldNotModifySourceOrTarget() {
        addToBoth(TENANT, "c1", "John", "Smith", "john@example.com");
        sap.add(TENANT, "c2", "Anna", "Nowak", "anna@example.com");
        target.add(TENANT, "c3", "Tom", "Brown", "tom@example.com");
        target.add(TENANT, "c1", "John", "Changed", "john@example.com");
        Map<String, NavigableMap<String, SapCandidate>> sourceBefore = sap.snapshot();
        Map<String, NavigableMap<String, SmartRecruitersCandidate>> targetBefore = target.snapshot();

        reconcile(TENANT);

        assertEquals(sourceBefore, sap.snapshot());
        assertEquals(targetBefore, target.snapshot());
        // write operations of the fake target fail the test if called; none were attempted
        assertEquals(0, target.writeAttempts);
        assertEquals(0, sap.singleReads);
    }

    @Test
    void shouldNotUseMigrationStateAsProofOfMatch() throws SQLException {
        sap.add(TENANT, "c1", "John", "Smith", "john@example.com");
        // the migration engine believes c1 was migrated, but the target does not have it
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "INSERT INTO candidate_migration (tenant_id, source_record_id, status) VALUES (?, 'c1', 'COMPLETED')")) {
            statement.setString(1, TENANT);
            statement.executeUpdate();
        }

        ReconciliationRun run = reconcile(TENANT);

        assertSummary(run, 1, 0, 0, 1, 0, 0);
        assertEquals("COMPLETED", migrationStatus("c1"));
    }

    @Test
    void shouldMarkRunFailedWhenTargetScanFailsAndKeepWorkingData() {
        for (int i = 1; i <= 5; i++) {
            addToBoth(TENANT, "c" + i, "First" + i, "Last" + i, "p" + i + "@example.com");
        }
        target.failingPage = 1;

        ReconciliationRun run = reconcile(TENANT);

        assertEquals(ReconciliationRunStatus.FAILED, run.status());
        assertNotNull(run.lastError());
        assertTrue(run.lastError().contains("SmartRecruiters GET candidates"));
        assertNotNull(run.completedAt());
        // no partial summary pretends to be a result
        assertSummary(run, 0, 0, 0, 0, 0, 0, ReconciliationRunStatus.FAILED);
        // source scan and the first target page are kept for diagnosis
        assertEquals(5, countItems(run.runId(), "source_seen"));
        assertEquals(2, countItems(run.runId(), "target_seen"));

        // a failed run does not block a new one, which starts from scratch
        target.failingPage = -1;
        ReconciliationRun retry = reconcile(TENANT);
        assertNotEquals(run.runId(), retry.runId());
        assertSummary(retry, 5, 5, 5, 0, 0, 0);
        assertEquals(ReconciliationRunStatus.FAILED, service.findRun(run.runId()).orElseThrow().status());
    }

    @Test
    void shouldPersistOnlySanitizedErrorWhenTargetResponseEchoesCandidateData() {
        sap.add(TENANT, "c1", "John", "Smith", "john@example.com");
        RestClient.Builder builder = RestClient.builder().baseUrl("http://sr.test");
        MockRestServiceServer srServer = MockRestServiceServer.bindTo(builder).build();
        srServer.expect(ExpectedCount.times(3), requestTo("http://sr.test/api/tenants/tenant-1/candidates?page=0&size=2"))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"email\": \"secret-person@example.com\", \"firstName\": \"SecretFirstName\"}"));
        service = new CandidateReconciliationService(sap,
                new RestSmartRecruitersClient(builder.build(),
                        new ExternalCallExecutor(CircuitBreaker.ofDefaults("sr"), 3, Duration.ofMillis(1), 2.0, Duration.ofMillis(5))),
                new CandidateMapper(), repository, PAGE_SIZE);

        ReconciliationRun run = reconcile(TENANT);

        assertEquals(ReconciliationRunStatus.FAILED, run.status());
        // read back from PostgreSQL
        assertEquals("SmartRecruiters GET candidates tenant-1 page 0 size 2 failed (TRANSIENT, HTTP 503)", run.lastError());
        srServer.verify();
    }

    @Test
    void shouldMarkRunFailedWhenSourceScanFails() {
        sap.add(TENANT, "c1", "John", "Smith", "john@example.com");
        sap.failingPage = 0;

        ReconciliationRun run = reconcile(TENANT);

        assertEquals(ReconciliationRunStatus.FAILED, run.status());
        assertEquals(List.of(), target.requestedPages);
    }

    @Test
    void shouldPaginateDifferenceDetails() {
        for (int i = 1; i <= 5; i++) {
            sap.add(TENANT, "c" + i, "First" + i, "Last" + i, "p" + i + "@example.com");
            target.add(TENANT, "c" + i, "Other" + i, "Last" + i, "p" + i + "@example.com");
        }
        ReconciliationRun run = reconcile(TENANT);

        ReconciliationItemPage first = service.findItems(run.runId(), ReconciliationResult.MISMATCHED, 0, 2);
        ReconciliationItemPage second = service.findItems(run.runId(), ReconciliationResult.MISMATCHED, 1, 2);
        ReconciliationItemPage last = service.findItems(run.runId(), ReconciliationResult.MISMATCHED, 2, 2);

        assertEquals(List.of("c1", "c2"), ids(first));
        assertTrue(first.hasNext());
        assertEquals(List.of("c3", "c4"), ids(second));
        assertTrue(second.hasNext());
        assertEquals(List.of("c5"), ids(last));
        assertFalse(last.hasNext());
        ReconciliationItem mismatch = first.items().getFirst();
        assertEquals(ReconciliationResult.MISMATCHED, mismatch.result());
        assertNotEquals(mismatch.sourceFingerprint(), mismatch.targetFingerprint());
        assertEquals(List.of(), service.findItems(run.runId(), ReconciliationResult.MISSING_IN_TARGET, 0, 2).items());
    }

    private ReconciliationRun reconcile(String tenantId) {
        ReconciliationRun created = service.startOrGetUnfinished(tenantId);
        service.runReconciliation(created.runId());
        return service.findRun(created.runId()).orElseThrow();
    }

    private void assertSingleMismatch(ReconciliationRun run) {
        assertSummary(run, 1, 1, 0, 0, 0, 1);
        ReconciliationItem mismatch = only(run, ReconciliationResult.MISMATCHED);
        assertEquals("c1", mismatch.externalId());
        assertNotEquals(mismatch.sourceFingerprint(), mismatch.targetFingerprint());
    }

    private ReconciliationItem only(ReconciliationRun run, ReconciliationResult result) {
        List<ReconciliationItem> items = service.findItems(run.runId(), result, 0, 100).items();
        assertEquals(1, items.size());
        return items.getFirst();
    }

    private static void assertSummary(ReconciliationRun run, long source, long target, long matched,
                                      long missing, long unexpected, long mismatched) {
        assertSummary(run, source, target, matched, missing, unexpected, mismatched, ReconciliationRunStatus.COMPLETED);
    }

    private static void assertSummary(ReconciliationRun run, long source, long target, long matched,
                                      long missing, long unexpected, long mismatched, ReconciliationRunStatus status) {
        assertEquals(status, run.status(), () -> "lastError: " + run.lastError());
        assertEquals(source, run.sourceCount(), "source");
        assertEquals(target, run.targetCount(), "target");
        assertEquals(matched, run.matchedCount(), "matched");
        assertEquals(missing, run.missingInTargetCount(), "missing in target");
        assertEquals(unexpected, run.unexpectedInTargetCount(), "unexpected in target");
        assertEquals(mismatched, run.mismatchedCount(), "mismatched");
    }

    private static List<String> ids(ReconciliationItemPage page) {
        return page.items().stream().map(ReconciliationItem::externalId).toList();
    }

    private void addToBoth(String tenantId, String id, String firstName, String lastName, String email) {
        sap.add(tenantId, id, firstName, lastName, email);
        target.add(tenantId, id, firstName, lastName, email);
    }

    private long countItems(UUID runId, String flagColumn) {
        String sql = "SELECT count(*) FROM candidate_reconciliation_item WHERE run_id = ? AND " + flagColumn;
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, runId);
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getLong(1);
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private String migrationStatus(String candidateId) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT status FROM candidate_migration WHERE tenant_id = ? AND source_record_id = ?")) {
            statement.setString(1, TENANT);
            statement.setString(2, candidateId);
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getString(1);
            }
        }
    }

    private static ExternalSystemException unavailable(String operation) {
        return ExternalSystemException.fromHttpClientFailure(operation, FailureType.TRANSIENT, new ResourceAccessException("Read timed out"));
    }

    /**
     * Paged source ordered by id, like the SAP API.
     */
    private static final class FakeSap implements SapClient {

        private final Map<String, NavigableMap<String, SapCandidate>> candidates = new HashMap<>();
        final List<Integer> requestedPages = new ArrayList<>();
        int failingPage = -1;
        int singleReads;

        void add(String tenantId, String id, String firstName, String lastName, String email) {
            candidates.computeIfAbsent(tenantId, t -> new TreeMap<>())
                    .put(id, new SapCandidate(id, tenantId, firstName, lastName, email));
        }

        Map<String, NavigableMap<String, SapCandidate>> snapshot() {
            Map<String, NavigableMap<String, SapCandidate>> copy = new HashMap<>();
            candidates.forEach((tenant, values) -> copy.put(tenant, new TreeMap<>(values)));
            return copy;
        }

        @Override
        public SapCandidate getCandidate(String tenantId, String candidateId) {
            singleReads++;
            return candidates.getOrDefault(tenantId, new TreeMap<>()).get(candidateId);
        }

        @Override
        public SapCandidatePage getCandidates(String tenantId, int page, int size) {
            requestedPages.add(page);
            if (page == failingPage) {
                throw unavailable("SAP GET candidates " + tenantId + " page " + page);
            }
            List<SapCandidate> all = new ArrayList<>(candidates.getOrDefault(tenantId, new TreeMap<>()).values());
            int from = Math.min(page * size, all.size());
            int to = Math.min(from + size, all.size());
            return new SapCandidatePage(all.subList(from, to), page, size, to < all.size());
        }
    }

    /**
     * Paged target ordered by externalId, like the SmartRecruiters API. Writes fail the test.
     */
    private static final class FakeTarget implements SmartRecruitersClient {

        private final Map<String, NavigableMap<String, SmartRecruitersCandidate>> candidates = new HashMap<>();
        final List<Integer> requestedPages = new ArrayList<>();
        int failingPage = -1;
        int writeAttempts;

        void add(String tenantId, String externalId, String firstName, String lastName, String email) {
            candidates.computeIfAbsent(tenantId, t -> new TreeMap<>())
                    .put(externalId, new SmartRecruitersCandidate(externalId, firstName, lastName, email));
        }

        Map<String, NavigableMap<String, SmartRecruitersCandidate>> snapshot() {
            Map<String, NavigableMap<String, SmartRecruitersCandidate>> copy = new HashMap<>();
            candidates.forEach((tenant, values) -> copy.put(tenant, new TreeMap<>(values)));
            return copy;
        }

        @Override
        public void createCandidate(String tenantId, SmartRecruitersCandidateRequest request) {
            writeAttempts++;
            throw new AssertionError("reconciliation must not write to the target");
        }

        @Override
        public void upsertCandidate(String tenantId, SmartRecruitersCandidateRequest request) {
            writeAttempts++;
            throw new AssertionError("reconciliation must not write to the target");
        }

        @Override
        public SmartRecruitersCandidatePage getCandidates(String tenantId, int page, int size) {
            requestedPages.add(page);
            if (page == failingPage) {
                throw unavailable("SmartRecruiters GET candidates " + tenantId + " page " + page);
            }
            List<SmartRecruitersCandidate> all =
                    new ArrayList<>(candidates.getOrDefault(tenantId, new TreeMap<>()).values());
            int from = Math.min(page * size, all.size());
            int to = Math.min(from + size, all.size());
            return new SmartRecruitersCandidatePage(all.subList(from, to), page, size, to < all.size());
        }
    }
}
