package com.dch.srstub;

public record SmartRecruitersCandidateRequest(
        String externalId,
        String firstName,
        String lastName,
        String email
) {
}
