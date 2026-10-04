package com.dch.smartrecruters.client;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.UnknownContentTypeException;

import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.OptionalInt;
import java.util.concurrent.TimeoutException;

/**
 * Failure of a call to an external system, safe to log and to persist.
 * <p>
 * Upstream responses may echo candidate data (PII), and Spring's HTTP exceptions put the response body
 * into their message. This exception therefore carries only controlled metadata - operation,
 * {@link FailureType}, {@link Kind} and the HTTP status when there was a response - and its message is
 * built from that metadata alone. The raw HTTP exception is deliberately <b>not</b> kept as cause, so no
 * stack trace or cause chain can print a remote body or a raw transport message. The only cause ever
 * retained is a {@link CallNotPermittedException}, which is generated locally and has no remote content.
 */
public class ExternalSystemException extends RuntimeException {

    /**
     * Broad technical category of the failure.
     */
    public enum Kind {
        /**
         * The upstream answered with an error status; see {@link #httpStatus()}.
         */
        HTTP_RESPONSE,
        /**
         * Connect or read timeout, no response.
         */
        TIMEOUT,
        /**
         * Other I/O problem without a response (connection refused/reset, DNS, ...).
         */
        TRANSPORT_ERROR,
        /**
         * The upstream answered, but the response could not be read (unexpected content or content type).
         */
        INVALID_RESPONSE,
        /**
         * Rejected locally by the circuit breaker; no request was sent.
         */
        CIRCUIT_OPEN
    }

    private final String operation;
    private final FailureType failureType;
    private final Kind kind;
    private final Integer httpStatus;

    private ExternalSystemException(
            String operation,
            FailureType failureType,
            Kind kind,
            Integer httpStatus,
            Throwable safeCause
    ) {
        super(safeMessage(operation, failureType, kind, httpStatus), safeCause);
        this.operation = operation;
        this.failureType = failureType;
        this.kind = kind;
        this.httpStatus = httpStatus;
    }

    /**
     * Translates a RestClient failure. Only the HTTP status and the exception type are inspected;
     * neither the raw exception nor its message (which may contain the response body) is retained.
     */
    public static ExternalSystemException fromHttpClientFailure(
            String operation,
            FailureType failureType,
            RestClientException failure
    ) {
        if (failure instanceof RestClientResponseException response) {
            return new ExternalSystemException(
                    operation, failureType, Kind.HTTP_RESPONSE, response.getStatusCode().value(), null
            );
        }
        if (failure instanceof UnknownContentTypeException unreadable) {
            return new ExternalSystemException(
                    operation, failureType, Kind.INVALID_RESPONSE, unreadable.getStatusCode().value(), null
            );
        }
        if (failure instanceof ResourceAccessException) {
            Kind kind = isTimeout(failure) ? Kind.TIMEOUT : Kind.TRANSPORT_ERROR;
            return new ExternalSystemException(operation, failureType, kind, null, null);
        }
        // e.g. the body of a successful response could not be converted
        return new ExternalSystemException(operation, failureType, Kind.INVALID_RESPONSE, null, null);
    }

    /**
     * Fail-fast rejection by an OPEN circuit breaker: always transient, no HTTP request was made.
     */
    public static ExternalSystemException circuitOpen(String operation, CallNotPermittedException rejection) {
        return new ExternalSystemException(operation, FailureType.TRANSIENT, Kind.CIRCUIT_OPEN, null, rejection);
    }

    public String operation() {
        return operation;
    }

    public FailureType failureType() {
        return failureType;
    }

    public Kind kind() {
        return kind;
    }

    /**
     * Status of the upstream response, empty when no response was received.
     */
    public OptionalInt httpStatus() {
        return httpStatus == null ? OptionalInt.empty() : OptionalInt.of(httpStatus);
    }

    public boolean isTransient() {
        return failureType == FailureType.TRANSIENT;
    }

    private static String safeMessage(String operation, FailureType failureType, Kind kind, Integer httpStatus) {
        String detail = switch (kind) {
            case HTTP_RESPONSE -> "HTTP " + httpStatus;
            case TIMEOUT -> "timeout";
            case TRANSPORT_ERROR -> "transport error";
            case INVALID_RESPONSE -> httpStatus == null ? "invalid response" : "invalid response, HTTP " + httpStatus;
            case CIRCUIT_OPEN -> "circuit breaker open";
        };
        return operation + " failed (" + failureType + ", " + detail + ")";
    }

    private static boolean isTimeout(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof SocketTimeoutException
                    || current instanceof HttpTimeoutException
                    || current instanceof TimeoutException) {
                return true;
            }
        }
        return false;
    }
}
