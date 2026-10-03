package com.dch.smartrecruters.client.sap;

public record SapCandidate(
        String id,
        String tenantId,
        String firstName,
        String lastName,
        String email
) {
}