package com.dch.smartrecruters.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "clients")
public record ClientProperties(
        Endpoint sap,
        Endpoint smartrecruiters
) {

    public record Endpoint(String baseUrl) {
    }
}
