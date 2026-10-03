package com.dch.smartrecruters.client.sap;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestClient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class RestSapClientTest {

    private MockRestServiceServer server;
    private RestSapClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://sap.test");
        server = MockRestServiceServer.bindTo(builder).build();
        client = new RestSapClient(builder.build());
    }

    @Test
    void shouldGetCandidateFromSap() {
        server.expect(requestTo("http://sap.test/api/tenants/tenant-1/candidates/candidate-1"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {
                          "id": "candidate-1",
                          "tenantId": "tenant-1",
                          "firstName": "John",
                          "lastName": "Smith",
                          "email": "john@example.com"
                        }
                        """, MediaType.APPLICATION_JSON));

        SapCandidate candidate = client.getCandidate("tenant-1", "candidate-1");

        assertEquals(
                new SapCandidate("candidate-1", "tenant-1", "John", "Smith", "john@example.com"),
                candidate
        );
        server.verify();
    }

    @Test
    void shouldPropagateServerError() {
        server.expect(requestTo("http://sap.test/api/tenants/tenant-1/candidates/candidate-1"))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

        assertThrows(
                HttpServerErrorException.class,
                () -> client.getCandidate("tenant-1", "candidate-1")
        );
    }
}
