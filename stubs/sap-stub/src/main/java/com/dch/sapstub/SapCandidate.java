package com.dch.sapstub;

public record SapCandidate(
        String id,
        String tenantId,
        String firstName,
        String lastName,
        String email
) {
}
