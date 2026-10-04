package com.dch.smartrecruters.client.smartrecruiters;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Candidate as stored in SmartRecruiters, limited to the fields this migration owns.
 * Target-generated data (id, timestamps) is not needed for verification and is ignored.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SmartRecruitersCandidate(
        String externalId,
        String firstName,
        String lastName,
        String email
) {
}
