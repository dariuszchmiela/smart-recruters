package com.dch.smartrecruters.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.retry.RetryPolicy;
import org.springframework.core.retry.RetryTemplate;
import org.springframework.web.client.RestClientException;

import java.time.Duration;
import java.util.function.Supplier;

/**
 * Runs a single external HTTP call with bounded retry for transient failures only.
 * Every {@link RestClientException} is translated to {@link ExternalSystemException}.
 */
public class ExternalCallExecutor {

    private static final Logger log = LoggerFactory.getLogger(ExternalCallExecutor.class);

    private final RetryTemplate retryTemplate;

    public ExternalCallExecutor(
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
                .predicate(e -> e instanceof ExternalSystemException ex && ex.isTransient())
                .build();

        this.retryTemplate = new RetryTemplate(retryPolicy);
    }

    public <T> T execute(String operation, Supplier<T> call) {
        // invoke() rethrows the last RuntimeException as-is when retries are exhausted
        // or when the predicate rejects the failure (permanent error)
        return retryTemplate.invoke(() -> callOnce(operation, call));
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
