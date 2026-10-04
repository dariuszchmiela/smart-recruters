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

    @Override
    public SapCandidatePage getCandidates(String tenantId, int page, int size) {
        SapCandidatePage candidates = executor.execute(
                "SAP GET candidates " + tenantId + " page " + page + " size " + size,
                () -> restClient.get()
                        .uri("/api/tenants/{tenantId}/candidates?page={page}&size={size}", tenantId, page, size)
                        .retrieve()
                        .body(SapCandidatePage.class)
        );

        if (candidates == null) {
            throw new IllegalStateException("SAP returned empty candidate page " + tenantId + ":" + page);
        }

        return candidates;
    }
}
