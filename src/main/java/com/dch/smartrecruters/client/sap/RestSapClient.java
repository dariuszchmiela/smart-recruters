package com.dch.smartrecruters.client.sap;

import com.dch.smartrecruters.client.ExternalCallExecutor;
import com.dch.smartrecruters.client.SapClient;
import org.springframework.web.client.RestClient;

public class RestSapClient implements SapClient {

    private final RestClient restClient;
    private final ExternalCallExecutor executor;

    public RestSapClient(RestClient restClient, ExternalCallExecutor executor) {
        this.restClient = restClient;
        this.executor = executor;
    }

    @Override
    public SapCandidate getCandidate(String tenantId, String candidateId) {
        SapCandidate candidate = executor.execute(
                "SAP GET candidate " + tenantId + ":" + candidateId,
                () -> restClient.get()
                        .uri("/api/tenants/{tenantId}/candidates/{candidateId}", tenantId, candidateId)
                        .retrieve()
                        .body(SapCandidate.class)
        );

        if (candidate == null) {
            throw new IllegalStateException("SAP returned empty candidate " + tenantId + ":" + candidateId);
        }

        return candidate;
    }
}
