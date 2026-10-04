package com.dch.sapstub;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SapCandidateStoreTest {

    @Test
    void shouldOrderPagesByCandidateIdRegardlessOfInsertionOrder() {
        SapCandidateStore store = new SapCandidateStore(List.of(
                new SapCandidate("c", "tenant-1", "C", "C", "c@example.com"),
                new SapCandidate("a", "tenant-1", "A", "A", "a@example.com"),
                new SapCandidate("b", "tenant-1", "B", "B", "b@example.com"),
                new SapCandidate("a", "tenant-2", "X", "X", "x@example.com")
        ));

        SapCandidatePage first = store.findPage("tenant-1", 0, 2);
        SapCandidatePage second = store.findPage("tenant-1", 1, 2);

        assertEquals(List.of("a", "b"), first.items().stream().map(SapCandidate::id).toList());
        assertTrue(first.hasNext());
        assertEquals(List.of("c"), second.items().stream().map(SapCandidate::id).toList());
        assertFalse(second.hasNext());
    }

    @Test
    void shouldReturnEveryBulkCandidateExactlyOnceAcrossPages() {
        SapCandidateStore store = new SapCandidateStore();
        List<String> ids = new ArrayList<>();
        int page = 0;
        SapCandidatePage current;

        do {
            current = store.findPage(SapCandidateStore.BULK_TENANT, page++, 100);
            current.items().forEach(candidate -> ids.add(candidate.id()));
        } while (current.hasNext());

        assertEquals(SapCandidateStore.BULK_CANDIDATES, ids.size());
        assertEquals(SapCandidateStore.BULK_CANDIDATES, ids.stream().distinct().count());
        assertEquals(ids.stream().sorted().toList(), ids);
        assertEquals(11, page);
    }
}
