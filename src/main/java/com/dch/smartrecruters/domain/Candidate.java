package com.dch.smartrecruters.domain;

public record Candidate(
        String id,
        String tenantId,
        String firstName,
        String lastName,
        String email
) {
}