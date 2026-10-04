package com.dch.smartrecruters.client;

import com.dch.smartrecruters.client.smartrecruiters.SmartRecruitersCandidatePage;
import com.dch.smartrecruters.client.smartrecruiters.SmartRecruitersCandidateRequest;

public interface SmartRecruitersClient {

    /**
     * Initial load: creates the candidate if tenantId + externalId does not exist yet,
     * otherwise leaves the existing candidate unchanged.
     */
    void createCandidate(
            String tenantId,
            SmartRecruitersCandidateRequest request
    );

    /**
     * Delta synchronization: creates the candidate, or replaces the data of the existing
     * candidate with the same tenantId + externalId. Never creates a duplicate.
     */
    void upsertCandidate(
            String tenantId,
            SmartRecruitersCandidateRequest request
    );

    /**
     * Read-only listing for verification.
     *
     * @param page zero-based page number; candidates are ordered by externalId
     * @param size maximum number of candidates on the page
     */
    SmartRecruitersCandidatePage getCandidates(String tenantId, int page, int size);
}
