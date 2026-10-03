package com.dch.smartrecruters.client.smartrecruiters;

import com.dch.smartrecruters.client.ExternalCallExecutor;
import com.dch.smartrecruters.client.SmartRecruitersClient;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

public class RestSmartRecruitersClient implements SmartRecruitersClient {

    private final RestClient restClient;
    private final ExternalCallExecutor executor;

    public RestSmartRecruitersClient(RestClient restClient, ExternalCallExecutor executor) {
        this.restClient = restClient;
        this.executor = executor;
    }

    /**
     * Safe to retry: the target is idempotent on tenantId + externalId.
     */
    @Override
    public void createCandidate(
            String tenantId,
            SmartRecruitersCandidateRequest request
    ) {
        executor.execute(
                "SmartRecruiters POST candidate " + tenantId + ":" + request.externalId(),
                () -> restClient.post()
                        .uri("/api/tenants/{tenantId}/candidates", tenantId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(request)
                        .retrieve()
                        .toBodilessEntity()
        );
    }
}
