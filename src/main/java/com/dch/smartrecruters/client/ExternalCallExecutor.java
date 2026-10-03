package com.dch.smartrecruters.client;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.retry.RetryPolicy;
import org.springframework.core.retry.RetryTemplate;
import org.springframework.web.client.RestClientException;

import java.time.Duration;
import java.util.function.Supplier;

/**
 * Runs a single external HTTP call as:
 * <pre>
 * CircuitBreaker (one per external system)
 *   -> Retry (bounded, transient failures only)
 *     -> HTTP
 * </pre>
 * One business call counts as one circuit breaker outcome, regardless of how many retries it needed.
 * Every {@link RestClientException} is translated to {@link ExternalSystemException}.
 */
public class ExternalCallExecutor {

    private static final Logger log = LoggerFactory.getLogger(ExternalCallExecutor.class);

    private final CircuitBreaker circuitBreaker;
    private final RetryTemplate retryTemplate;

    public ExternalCallExecutor(
            CircuitBreaker circuitBreaker,
            int maxAttempts,
            Duration initialBackoff,
            double multiplier,
            Duration maxBackoff
    ) {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be >= 1");
        }

        RetryPolicy retryPolicy = RetryPolicy.builder()
                .maxRetries(maxAttempts - 1)
                .delay(initialBackoff)
                .multiplier(multiplier)
                .maxDelay(maxBackoff)
                .predicate(ExternalCallExecutor::isTransientFailure)
                .build();

        this.circuitBreaker = circuitBreaker;
        this.retryTemplate = new RetryTemplate(retryPolicy);

        circuitBreaker.getEventPublisher().onStateTransition(event ->
                log.warn("Circuit breaker '{}' {}", event.getCircuitBreakerName(), event.getStateTransition())
        );
    }

    /**
     * The single definition of "transient" used both for retry and for circuit breaker failures.
     */
    public static boolean isTransientFailure(Throwable throwable) {
        return throwable instanceof ExternalSystemException exception && exception.isTransient();
    }

    public <T> T execute(String operation, Supplier<T> call) {
        try {
            // invoke() rethrows the last RuntimeException as-is when retries are exhausted
            // or when the predicate rejects the failure (permanent error)
            return circuitBreaker.executeSupplier(
                    () -> retryTemplate.invoke(() -> callOnce(operation, call))
            );
        } catch (CallNotPermittedException e) {
            // OPEN (or HALF_OPEN with no free trial slot): fail fast, no HTTP, no retry
            log.warn("{} rejected: {}", operation, e.getMessage());
            throw new ExternalSystemException(operation, FailureType.TRANSIENT, e);
        }
    }

    private <T> T callOnce(String operation, Supplier<T> call) {
        try {
            return call.get();
        } catch (RestClientException e) {
            FailureType failureType = HttpFailureClassifier.classify(e);
            log.warn("{} failed with {} error: {}", operation, failureType, e.getMessage());
            throw new ExternalSystemException(operation, failureType, e);
        }
    }
}
