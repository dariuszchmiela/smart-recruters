package com.dch.smartrecruters.config;

import com.dch.smartrecruters.client.ExternalCallExecutor;
import com.dch.smartrecruters.client.SapClient;
import com.dch.smartrecruters.client.SmartRecruitersClient;
import com.dch.smartrecruters.client.sap.RestSapClient;
import com.dch.smartrecruters.client.smartrecruiters.RestSmartRecruitersClient;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

import java.time.Clock;

@Configuration
@EnableConfigurationProperties(ClientProperties.class)
public class ClientConfiguration {

    public static final String SAP = "sap";
    public static final String SMARTRECRUITERS = "smartrecruiters";

    @Bean
    public CircuitBreakerRegistry circuitBreakerRegistry(ClientProperties properties) {
        return CircuitBreakerRegistry.of(circuitBreakerConfig(properties.circuitBreaker(), Clock.systemUTC()));
    }

    @Bean
    public SapClient sapClient(
            RestClient.Builder builder,
            ClientHttpRequestFactoryBuilder<?> requestFactoryBuilder,
            ClientProperties properties,
            CircuitBreakerRegistry circuitBreakerRegistry
    ) {
        return new RestSapClient(
                restClient(builder, requestFactoryBuilder, properties.sap()),
                callExecutor(circuitBreakerRegistry.circuitBreaker(SAP), properties.retry())
        );
    }

    @Bean
    public SmartRecruitersClient smartRecruitersClient(
            RestClient.Builder builder,
            ClientHttpRequestFactoryBuilder<?> requestFactoryBuilder,
            ClientProperties properties,
            CircuitBreakerRegistry circuitBreakerRegistry
    ) {
        return new RestSmartRecruitersClient(
                restClient(builder, requestFactoryBuilder, properties.smartrecruiters()),
                callExecutor(circuitBreakerRegistry.circuitBreaker(SMARTRECRUITERS), properties.retry())
        );
    }

    static CircuitBreakerConfig circuitBreakerConfig(
            ClientProperties.CircuitBreakerSettings settings,
            Clock clock
    ) {
        return CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(settings.slidingWindowSize())
                .minimumNumberOfCalls(settings.minimumNumberOfCalls())
                .failureRateThreshold(settings.failureRateThreshold())
                .waitDurationInOpenState(settings.waitDurationInOpenState())
                .permittedNumberOfCallsInHalfOpenState(settings.permittedCallsInHalfOpenState())
                // only TRANSIENT failures count; PERMANENT ones mean the upstream did respond
                .recordException(ExternalCallExecutor::isTransientFailure)
                .clock(clock)
                .build();
    }

    static ExternalCallExecutor callExecutor(CircuitBreaker circuitBreaker, ClientProperties.Retry retry) {
        return new ExternalCallExecutor(
                circuitBreaker,
                retry.maxAttempts(),
                retry.initialBackoff(),
                retry.multiplier(),
                retry.maxBackoff()
        );
    }

    private RestClient restClient(
            RestClient.Builder builder,
            ClientHttpRequestFactoryBuilder<?> requestFactoryBuilder,
            ClientProperties.Endpoint endpoint
    ) {
        HttpClientSettings settings = HttpClientSettings.defaults()
                .withTimeouts(endpoint.connectTimeout(), endpoint.readTimeout());

        return builder.clone()
                .baseUrl(endpoint.baseUrl())
                .requestFactory(requestFactoryBuilder.build(settings))
                .build();
    }
}
