package com.dch.smartrecruters.config;

import com.dch.smartrecruters.client.SapClient;
import com.dch.smartrecruters.client.SmartRecruitersClient;
import com.dch.smartrecruters.mapper.CandidateMapper;
import com.dch.smartrecruters.service.CandidateReconciliationService;
import com.dch.smartrecruters.service.ReconciliationRunLauncher;
import com.dch.smartrecruters.state.ReconciliationRepository;
import com.dch.smartrecruters.state.jdbc.JdbcReconciliationRepository;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

@Configuration
@EnableConfigurationProperties(ReconciliationProperties.class)
public class ReconciliationConfiguration {

    @Bean
    public ReconciliationRepository reconciliationRepository(
            DataSource dataSource,
            ReconciliationProperties properties
    ) {
        return new JdbcReconciliationRepository(dataSource, properties.leaseTimeout());
    }

    @Bean
    public CandidateReconciliationService candidateReconciliationService(
            SapClient sapClient,
            SmartRecruitersClient smartRecruitersClient,
            CandidateMapper candidateMapper,
            ReconciliationRepository reconciliationRepository,
            ReconciliationProperties properties
    ) {
        return new CandidateReconciliationService(
                sapClient,
                smartRecruitersClient,
                candidateMapper,
                reconciliationRepository,
                properties.pageSize()
        );
    }

    @Bean(destroyMethod = "close")
    public ReconciliationRunLauncher reconciliationRunLauncher(
            CandidateReconciliationService candidateReconciliationService,
            ReconciliationProperties properties
    ) {
        return new ReconciliationRunLauncher(
                candidateReconciliationService,
                properties.maxConcurrentRuns(),
                properties.queueCapacity(),
                properties.recoveryInterval()
        );
    }

    /**
     * The recovery scan starts only once the application is ready, i.e. after Flyway migrated the schema.
     */
    @Bean
    public ApplicationListener<ApplicationReadyEvent> reconciliationRecoveryStarter(
            ReconciliationRunLauncher reconciliationRunLauncher
    ) {
        return event -> reconciliationRunLauncher.start();
    }
}
