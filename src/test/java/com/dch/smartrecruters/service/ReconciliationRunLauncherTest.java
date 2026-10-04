package com.dch.smartrecruters.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReconciliationRunLauncherTest {

    private final CandidateReconciliationService service = mock(CandidateReconciliationService.class);
    private final CountDownLatch release = new CountDownLatch(1);
    private ReconciliationRunLauncher launcher;

    @AfterEach
    void tearDown() throws InterruptedException {
        release.countDown();
        launcher.close();
    }

    @Test
    void shouldRunSubmittedRunInBackground() {
        launcher = new ReconciliationRunLauncher(service, 1, 1, Duration.ofMinutes(1));
        UUID runId = UUID.randomUUID();

        assertTrue(launcher.submit(runId));

        verify(service, timeout(5_000)).runReconciliation(runId);
    }

    @Test
    void shouldBoundConcurrencyAndQueue() throws InterruptedException {
        launcher = new ReconciliationRunLauncher(service, 1, 1, Duration.ofMinutes(1));
        CountDownLatch running = new CountDownLatch(1);
        doAnswer(invocation -> {
            running.countDown();
            release.await();
            return null;
        }).when(service).runReconciliation(any());

        assertTrue(launcher.submit(UUID.randomUUID()));
        assertTrue(running.await(5, TimeUnit.SECONDS));
        assertTrue(launcher.submit(UUID.randomUUID()));

        // one running + one queued: the third stays PENDING in the database for the recovery scan
        assertFalse(launcher.submit(UUID.randomUUID()));
    }

    @Test
    void shouldFailAbandonedRunsAndStartPendingOnesInRecoveryScan() {
        UUID pending = UUID.randomUUID();
        when(service.findPendingRuns(anyInt())).thenReturn(List.of(pending));
        launcher = new ReconciliationRunLauncher(service, 1, 10, Duration.ofMinutes(1));

        launcher.start();

        verify(service, timeout(5_000)).failAbandonedRuns();
        verify(service, timeout(5_000)).runReconciliation(pending);
    }
}
