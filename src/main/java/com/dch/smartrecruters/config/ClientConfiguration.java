package com.dch.smartrecruters.config;

import com.dch.smartrecruters.client.ExternalCallExecutor;
import com.dch.smartrecruters.client.SapClient;
import com.dch.smartrecruters.client.SmartRecruitersClient;
import com.dch.smartrecruters.client.sap.RestSapClient;
import com.dch.smartrecruters.client.smartrecruiters.RestSmartRecruitersClient;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
@EnableConfigurationProperties(ClientProperties.class)
public class ClientConfiguration {

    @Bean
    public ExternalCallExecutor externalCallExecutor(ClientProperties properties) {
        ClientProperties.Retry retry = properties.retry();
        return new ExternalCallExecutor(
                retry.maxAttempts(),
                retry.initialBackoff(),
                retry.multiplier(),
                retry.maxBackoff()
        );
    }

    @Bean
    public SapClient sapClient(
            RestClient.Builder builder,
            ClientHttpRequestFactoryBuilder<?> requestFactoryBuilder,
            ClientProperties properties,
            ExternalCallExecutor executor
    ) {
        return new RestSapClient(
                restClient(builder, requestFactoryBuilder, properties.sap()),
                executor
        );
    }

    @Bean
    public SmartRecruitersClient smartRecruitersClient(
            RestClient.Builder builder,
            ClientHttpRequestFactoryBuilder<?> requestFactoryBuilder,
            ClientProperties properties,
            ExternalCallExecutor executor
    ) {
        return new RestSmartRecruitersClient(
                restClient(builder, requestFactoryBuilder, properties.smartrecruiters()),
                executor
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
