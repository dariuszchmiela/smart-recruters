package com.dch.smartrecruters.client;

import com.dch.smartrecruters.client.smartrecruiters.SmartRecruitersCandidateRequest;

public interface SmartRecruitersClient {

    void createCandidate(
            String tenantId,
            SmartRecruitersCandidateRequest request
    );
}