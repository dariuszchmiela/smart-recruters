package com.dch.srstub;

import org.junit.jupiter.api.Test;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
