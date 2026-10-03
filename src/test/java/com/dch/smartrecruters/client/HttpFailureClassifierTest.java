package com.dch.smartrecruters.client;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.net.SocketTimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;

class HttpFailureClassifierTest {

    @ParameterizedTest
    @ValueSource(ints = {408, 429, 502, 503, 504})
    void shouldClassifyRetryableStatusesAsTransient(int status) {
        assertEquals(FailureType.TRANSIENT, HttpFailureClassifier.classify(responseError(status)));
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 401, 403, 404, 409, 422, 500, 501})
    void shouldClassifyOtherStatusesAsPermanent(int status) {
        assertEquals(FailureType.PERMANENT, HttpFailureClassifier.classify(responseError(status)));
    }

    @Test
    void shouldClassifyTimeoutAsTransient() {
        ResourceAccessException timeout =
                new ResourceAccessException("Read timed out", new SocketTimeoutException("Read timed out"));

        assertEquals(FailureType.TRANSIENT, HttpFailureClassifier.classify(timeout));
    }

    @Test
    void shouldClassifyUnknownClientErrorAsPermanent() {
        assertEquals(
                FailureType.PERMANENT,
                HttpFailureClassifier.classify(new RestClientException("Cannot extract response"))
        );
    }

    private RestClientResponseException responseError(int status) {
        HttpStatusCode code = HttpStatusCode.valueOf(status);
        return code.is4xxClientError()
                ? new HttpClientErrorException(code)
                : new HttpServerErrorException(code);
    }
}
