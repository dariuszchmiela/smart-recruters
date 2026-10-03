package com.dch.smartrecruters.client;

import com.dch.smartrecruters.domain.Candidate;

public interface SapClient {

    Candidate getCandidate(String tenantId, String candidateId);
}