package com.dch.srstub;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Component
public class CandidateStore {

    private final ConcurrentMap<Key, StoredCandidate> candidates = new ConcurrentHashMap<>();

    /**
     * Creates the candidate only if no candidate exists for tenantId + externalId.
     * The check and the insert are a single atomic operation.
     */
    public CreateResult create(String tenantId, SmartRecruitersCandidateRequest request) {
        Key key = new Key(tenantId, request.externalId());
        StoredCandidate candidate = new StoredCandidate(
                UUID.randomUUID().toString(),
                tenantId,
                request.externalId(),
                request.firstName(),
                request.lastName(),
                request.email(),
                Instant.now()
        );

        StoredCandidate existing = candidates.putIfAbsent(key, candidate);

        return existing == null
                ? new CreateResult(candidate, true)
                : new CreateResult(existing, false);
    }

    public Optional<StoredCandidate> find(String tenantId, String externalId) {
        return Optional.ofNullable(candidates.get(new Key(tenantId, externalId)));
    }

    public List<StoredCandidate> findAll(String tenantId) {
        return candidates.values().stream()
                .filter(candidate -> candidate.tenantId().equals(tenantId))
                .sorted(Comparator.comparing(StoredCandidate::createdAt))
                .toList();
    }

    public record CreateResult(StoredCandidate candidate, boolean created) {
    }

    private record Key(String tenantId, String externalId) {
    }
}
