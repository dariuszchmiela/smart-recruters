package com.dch.smartrecruters.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "migration")
public record MigrationProperties(
        Duration claimTimeout
) {
}
