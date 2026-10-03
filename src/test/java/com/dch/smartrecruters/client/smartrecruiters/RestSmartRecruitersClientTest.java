package com.dch.smartrecruters.client.smartrecruiters;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

class RestSmartRecruitersClientTest {

    private MockRestServiceServer server;
    private RestSmartRecruitersClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://sr.test");
        server = MockRestServiceServer.bindTo(builder).build();
        client = new RestSmartRecruitersClient(builder.build());
    }

    @Test
    void shouldPostCandidateToTenantEndpoint() {
        server.expect(requestTo("http://sr.test/api/tenants/tenant-1/candidates"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(content().json("""
                        {
                          "externalId": "candidate-1",
                          "firstName": "John",
                          "lastName": "Smith",
                          "email": "john@example.com"
                        }
                        """))
                .andRespond(withStatus(HttpStatus.CREATED));

        client.createCandidate(
                "tenant-1",
                new SmartRecruitersCandidateRequest("candidate-1", "John", "Smith", "john@example.com")
        );

        server.verify();
    }
}
