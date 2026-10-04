package com.dch.sapstub;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.stream.IntStream;
import java.util.stream.Stream;

@Component
public class SapCandidateStore {

    static final String BULK_TENANT = "tenant-bulk";
    static final int BULK_CANDIDATES = 1_050;

    // per tenant, ordered by candidate id: deterministic pages
    private final Map<String, NavigableMap<String, SapCandidate>> candidatesByTenant = new ConcurrentHashMap<>();

    public SapCandidateStore() {
        this(Stream.concat(
                Stream.of(
                        new SapCandidate("candidate-1", "tenant-1", "John", "Smith", "john.smith@example.com"),
                        new SapCandidate("candidate-2", "tenant-1", "Anna", "Nowak", "anna.nowak@example.com"),
                        new SapCandidate("candidate-3", "tenant-1", "Piotr", "Kowalski", ""),
                        new SapCandidate("candidate-1", "tenant-2", "Maria", "Garcia", "maria.garcia@example.com"),
                        new SapCandidate("candidate-2", "tenant-2", "Tom", "Brown", "tom.brown@example.com")
                ),
                bulkTenant()
        ).toList());
    }

    SapCandidateStore(List<SapCandidate> candidates) {
        candidates.forEach(this::save);
    }

    public Optional<SapCandidate> find(String tenantId, String candidateId) {
        return Optional.ofNullable(candidatesByTenant.getOrDefault(tenantId, new ConcurrentSkipListMap<>()).get(candidateId));
    }

    /**
     * Creates or replaces the candidate (simulates a change in the source system).
     */
    public void save(SapCandidate candidate) {
        candidatesByTenant
                .computeIfAbsent(candidate.tenantId(), tenantId -> new ConcurrentSkipListMap<>())
                .put(candidate.id(), candidate);
    }

    /**
     * Zero-based page of the tenant's candidates ordered by id. Unknown tenant = empty page.
     */
    public SapCandidatePage findPage(String tenantId, int page, int size) {
        List<SapCandidate> all = new ArrayList<>(candidatesByTenant.getOrDefault(tenantId, new ConcurrentSkipListMap<>()).values());
        long from = (long) page * size;

        if (from >= all.size()) {
            return new SapCandidatePage(List.of(), page, size, false);
        }

        int to = (int) Math.min(from + size, all.size());
        return new SapCandidatePage(List.copyOf(all.subList((int) from, to)), page, size, to < all.size());
    }

    /**
     * Larger data set for local batch runs; every 100th candidate has no email (fails validation).
     */
    private static Stream<SapCandidate> bulkTenant() {
        return IntStream.rangeClosed(1, BULK_CANDIDATES)
                .mapToObj(i -> new SapCandidate(
                        "candidate-%05d".formatted(i),
                        BULK_TENANT,
                        "First" + i,
                        "Last" + i,
                        i % 100 == 0 ? "" : "person%05d@example.com".formatted(i)
                ));
    }
}
