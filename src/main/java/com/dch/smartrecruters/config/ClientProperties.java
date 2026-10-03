package com.dch.smartrecruters.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "clients")
public record ClientProperties(
        Endpoint sap,
        Endpoint smartrecruiters,
        Retry retry,
        CircuitBreakerSettings circuitBreaker
) {

    public record Endpoint(
            String baseUrl,
            Duration connectTimeout,
            Duration readTimeout
    ) {
    }

    public record Retry(
            int maxAttempts,
            Duration initialBackoff,
            double multiplier,
            Duration maxBackoff
    ) {
    }

    /**
     * Shared settings; every external system gets its own circuit breaker instance and state.
     */
    public record CircuitBreakerSettings(
            int slidingWindowSize,
            int minimumNumberOfCalls,
            float failureRateThreshold,
            Duration waitDurationInOpenState,
            int permittedCallsInHalfOpenState
    ) {
    }
}
