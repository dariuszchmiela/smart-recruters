package com.dch.smartrecruters.client;

import com.dch.smartrecruters.client.sap.SapCandidate;
import com.dch.smartrecruters.client.sap.SapCandidatePage;

public interface SapClient {

    SapCandidate getCandidate(String tenantId, String candidateId);

    /**
     * @param page zero-based page number
     * @param size maximum number of candidates on the page
     */
    SapCandidatePage getCandidates(String tenantId, int page, int size);
}
