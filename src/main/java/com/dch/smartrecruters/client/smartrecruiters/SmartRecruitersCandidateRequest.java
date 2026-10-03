package com.dch.smartrecruters.client.smartrecruiters;

public record SmartRecruitersCandidateRequest(
        String externalId,
        String firstName,
        String lastName,
        String email
) {
}