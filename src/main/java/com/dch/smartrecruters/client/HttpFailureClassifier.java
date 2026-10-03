package com.dch.smartrecruters.client;

import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.util.Set;

public final class HttpFailureClassifier {

    private static final Set<Integer> TRANSIENT_STATUSES = Set.of(
            408, // Request Timeout
            429, // Too Many Requests
            502, // Bad Gateway
            503, // Service Unavailable
            504  // Gateway Timeout
    );

    private HttpFailureClassifier() {
    }

    public static FailureType classify(RestClientException exception) {
        if (exception instanceof RestClientResponseException response) {
            return TRANSIENT_STATUSES.contains(response.getStatusCode().value())
                    ? FailureType.TRANSIENT
                    : FailureType.PERMANENT;
        }

        // connect/read timeout, connection refused/reset - no HTTP response at all
        if (exception instanceof ResourceAccessException) {
            return FailureType.TRANSIENT;
        }

        // e.g. response body cannot be deserialized
        return FailureType.PERMANENT;
    }
}
