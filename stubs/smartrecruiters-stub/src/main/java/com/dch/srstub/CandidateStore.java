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
     * Creates the candidate only if no candidate exists for tenantId + externalId; an existing one is left unchanged.
     * The check and the insert are a single atomic operation.
     */
    public CreateResult create(String tenantId, SmartRecruitersCandidateRequest request) {
        Key key = new Key(tenantId, request.externalId());
        Instant now = Instant.now();
        StoredCandidate candidate = new StoredCandidate(
                UUID.randomUUID().toString(),
                tenantId,
                request.externalId(),
                request.firstName(),
                request.lastName(),
                request.email(),
                now,
                now
        );

        StoredCandidate existing = candidates.putIfAbsent(key, candidate);

        return existing == null
                ? new CreateResult(candidate, true)
                : new CreateResult(existing, false);
    }

    /**
     * Creates the candidate, or replaces the data of the existing one for tenantId + externalId,
     * keeping its id and createdAt. Atomic per key, so concurrent upserts never create duplicates.
     */
    public CreateResult upsert(String tenantId, SmartRecruitersCandidateRequest request) {
        Key key = new Key(tenantId, request.externalId());
        boolean[] created = {false};

        StoredCandidate stored = candidates.compute(key, (ignored, existing) -> {
            Instant now = Instant.now();
            if (existing == null) {
                created[0] = true;
                return new StoredCandidate(
                        UUID.randomUUID().toString(), tenantId, request.externalId(),
                        request.firstName(), request.lastName(), request.email(), now, now
                );
            }
            return new StoredCandidate(
                    existing.id(), tenantId, request.externalId(),
                    request.firstName(), request.lastName(), request.email(), existing.createdAt(), now
            );
        });

        return new CreateResult(stored, created[0]);
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

    /**
     * Zero-based page of the tenant's candidates ordered by externalId (deterministic).
     * Sorts on every call - fine for a local stub.
     */
    public CandidatePage findPage(String tenantId, int page, int size) {
        List<StoredCandidate> all = candidates.values().stream()
                .filter(candidate -> candidate.tenantId().equals(tenantId))
                .sorted(Comparator.comparing(StoredCandidate::externalId))
                .toList();
        long from = (long) page * size;

        if (from >= all.size()) {
            return new CandidatePage(List.of(), page, size, false);
        }

        int to = (int) Math.min(from + size, all.size());
        return new CandidatePage(all.subList((int) from, to), page, size, to < all.size());
    }

    public record CreateResult(StoredCandidate candidate, boolean created) {
    }

    private record Key(String tenantId, String externalId) {
    }
}
