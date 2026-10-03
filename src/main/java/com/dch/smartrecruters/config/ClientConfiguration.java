package com.dch.smartrecruters.config;

import com.dch.smartrecruters.client.SapClient;
import com.dch.smartrecruters.client.SmartRecruitersClient;
import com.dch.smartrecruters.client.sap.RestSapClient;
import com.dch.smartrecruters.client.smartrecruiters.RestSmartRecruitersClient;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
@EnableConfigurationProperties(ClientProperties.class)
public class ClientConfiguration {

    @Bean
    public SapClient sapClient(RestClient.Builder builder, ClientProperties properties) {
        return new RestSapClient(
                builder.clone()
                        .baseUrl(properties.sap().baseUrl())
                        .build()
        );
    }

    @Bean
    public SmartRecruitersClient smartRecruitersClient(RestClient.Builder builder, ClientProperties properties) {
        return new RestSmartRecruitersClient(
                builder.clone()
                        .baseUrl(properties.smartrecruiters().baseUrl())
                        .build()
        );
    }
}
