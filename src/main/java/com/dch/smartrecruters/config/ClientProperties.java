package com.dch.smartrecruters.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "clients")
public record ClientProperties(
        Endpoint sap,
        Endpoint smartrecruiters,
        Retry retry
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
}
