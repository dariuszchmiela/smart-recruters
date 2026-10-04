package com.dch.srstub;

import org.junit.jupiter.api.Test;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class CandidateStoreTest {

    @Test
    void shouldCreateExactlyOneCandidateForConcurrentDuplicates() throws Exception {
        CandidateStore store = new CandidateStore();
        SmartRecruitersCandidateRequest request =
                new SmartRecruitersCandidateRequest("candidate-1", "John", "Smith", "john@example.com");

        Callable<Boolean> create = () -> store.create("tenant-1", request).created();

        long created;
        try (ExecutorService executor = Executors.newFixedThreadPool(8)) {
            var futures = IntStream.range(0, 50)
                    .mapToObj(i -> executor.submit(create))
                    .toList();

            created = 0;
            for (Future<Boolean> future : futures) {
                if (future.get()) {
                    created++;
                }
            }
        }

        assertEquals(1, created);
        assertEquals(1, store.findAll("tenant-1").size());
    }

    @Test
    void shouldKeepOneCandidateForConcurrentUpsertsAndKeepItsIdentity() throws Exception {
        CandidateStore store = new CandidateStore();
        String id = store.create("tenant-1",
                new SmartRecruitersCandidateRequest("candidate-1", "John", "Smith", "john@example.com"))
                .candidate().id();

        try (ExecutorService executor = Executors.newFixedThreadPool(8)) {
            var futures = IntStream.range(0, 50)
                    .mapToObj(i -> executor.submit(() -> store.upsert("tenant-1",
                            new SmartRecruitersCandidateRequest("candidate-1", "John", "Smith", "v" + i + "@example.com"))))
                    .toList();
            for (Future<CandidateStore.CreateResult> future : futures) {
                assertFalse(future.get().created());
            }
        }

        assertEquals(1, store.findAll("tenant-1").size());
        assertEquals(id, store.find("tenant-1", "candidate-1").orElseThrow().id());
    }
}
