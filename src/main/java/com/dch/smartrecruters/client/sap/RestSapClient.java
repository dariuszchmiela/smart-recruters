package com.dch.smartrecruters.client.sap;

import com.dch.smartrecruters.client.SapClient;
import org.springframework.web.client.RestClient;

public class RestSapClient implements SapClient {

    private final RestClient restClient;

    public RestSapClient(RestClient restClient) {
        this.restClient = restClient;
    }

    @Override
    public SapCandidate getCandidate(String tenantId, String candidateId) {
        SapCandidate candidate = restClient.get()
                .uri("/api/tenants/{tenantId}/candidates/{candidateId}", tenantId, candidateId)
                .retrieve()
                .body(SapCandidate.class);

        if (candidate == null) {
            throw new IllegalStateException("SAP returned empty candidate " + tenantId + ":" + candidateId);
        }

        return candidate;
    }
}
