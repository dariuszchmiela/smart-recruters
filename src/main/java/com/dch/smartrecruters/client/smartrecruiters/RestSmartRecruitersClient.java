package com.dch.smartrecruters.client.smartrecruiters;

import com.dch.smartrecruters.client.SmartRecruitersClient;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

public class RestSmartRecruitersClient implements SmartRecruitersClient {

    private final RestClient restClient;

    public RestSmartRecruitersClient(RestClient restClient) {
        this.restClient = restClient;
    }

    @Override
    public void createCandidate(
            String tenantId,
            SmartRecruitersCandidateRequest request
    ) {
        restClient.post()
                .uri("/api/tenants/{tenantId}/candidates", tenantId)
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .toBodilessEntity();
    }
}
