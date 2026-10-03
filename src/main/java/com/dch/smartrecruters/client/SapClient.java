package com.dch.smartrecruters.client;

import com.dch.smartrecruters.client.sap.SapCandidate;

public interface SapClient {

    SapCandidate getCandidate(String tenantId, String candidateId);
}