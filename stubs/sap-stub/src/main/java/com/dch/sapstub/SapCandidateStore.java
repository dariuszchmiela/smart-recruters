package com.dch.sapstub;

import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

@Component
public class SapCandidateStore {

    static final String BULK_TENANT = "tenant-bulk";
    static final int BULK_CANDIDATES = 1_050;

    private final Map<Key, SapCandidate> candidates;
    private final Map<String, List<SapCandidate>> candidatesByTenant;

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
        this.candidates = candidates.stream()
                .collect(Collectors.toUnmodifiableMap(
                        candidate -> new Key(candidate.tenantId(), candidate.id()),
                        Function.identity()
                ));
        // deterministic page order: candidate id
        this.candidatesByTenant = candidates.stream()
                .collect(Collectors.groupingBy(
                        SapCandidate::tenantId,
                        Collectors.collectingAndThen(
                                Collectors.toList(),
                                list -> list.stream().sorted(Comparator.comparing(SapCandidate::id)).toList()
                        )
                ));
    }

    public Optional<SapCandidate> find(String tenantId, String candidateId) {
        return Optional.ofNullable(candidates.get(new Key(tenantId, candidateId)));
    }

    /**
     * Zero-based page of the tenant's candidates ordered by id. Unknown tenant = empty page.
     */
    public SapCandidatePage findPage(String tenantId, int page, int size) {
        List<SapCandidate> all = candidatesByTenant.getOrDefault(tenantId, List.of());
        long from = (long) page * size;

        if (from >= all.size()) {
            return new SapCandidatePage(List.of(), page, size, false);
        }

        int to = (int) Math.min(from + size, all.size());
        return new SapCandidatePage(all.subList((int) from, to), page, size, to < all.size());
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

    private record Key(String tenantId, String candidateId) {
    }
}
