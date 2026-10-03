package com.dch.sapstub;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
public class SapCandidateStore {

    private final Map<Key, SapCandidate> candidates;

    public SapCandidateStore() {
        this(List.of(
                new SapCandidate("candidate-1", "tenant-1", "John", "Smith", "john.smith@example.com"),
                new SapCandidate("candidate-2", "tenant-1", "Anna", "Nowak", "anna.nowak@example.com"),
                new SapCandidate("candidate-3", "tenant-1", "Piotr", "Kowalski", ""),
                new SapCandidate("candidate-1", "tenant-2", "Maria", "Garcia", "maria.garcia@example.com"),
                new SapCandidate("candidate-2", "tenant-2", "Tom", "Brown", "tom.brown@example.com")
        ));
    }

    SapCandidateStore(List<SapCandidate> candidates) {
        this.candidates = candidates.stream()
                .collect(Collectors.toUnmodifiableMap(
                        candidate -> new Key(candidate.tenantId(), candidate.id()),
                        Function.identity()
                ));
    }

    public Optional<SapCandidate> find(String tenantId, String candidateId) {
        return Optional.ofNullable(candidates.get(new Key(tenantId, candidateId)));
    }

    private record Key(String tenantId, String candidateId) {
    }
}
