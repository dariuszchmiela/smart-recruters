package com.dch.smartrecruters.config;

import com.dch.smartrecruters.client.SapClient;
import com.dch.smartrecruters.client.SmartRecruitersClient;
import com.dch.smartrecruters.mapper.CandidateMapper;
import com.dch.smartrecruters.service.CandidateBatchMigrationService;
import com.dch.smartrecruters.service.CandidateDeltaService;
import com.dch.smartrecruters.service.CandidateMigrationService;
import com.dch.smartrecruters.service.TenantMigrationJobLauncher;
import com.dch.smartrecruters.state.CandidateDeltaEventRepository;
import com.dch.smartrecruters.state.MigrationRecordRepository;
import com.dch.smartrecruters.state.TenantMigrationJobRepository;
import com.dch.smartrecruters.validation.CandidateValidator;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MigrationServiceConfiguration {

    @Bean
    public CandidateMapper candidateMapper() {
        return new CandidateMapper();
    }

    @Bean
    public CandidateValidator candidateValidator() {
        return new CandidateValidator();
    }

    @Bean
    public CandidateMigrationService candidateMigrationService(
            SapClient sapClient,
            CandidateMapper candidateMapper,
            CandidateValidator candidateValidator,
            SmartRecruitersClient smartRecruitersClient,
            MigrationRecordRepository migrationRecordRepository
    ) {
        return new CandidateMigrationService(
                sapClient,
                candidateMapper,
                candidateValidator,
                smartRecruitersClient,
                migrationRecordRepository
        );
    }

    @Bean
    public CandidateDeltaService candidateDeltaService(
            SapClient sapClient,
            CandidateMapper candidateMapper,
            CandidateValidator candidateValidator,
            SmartRecruitersClient smartRecruitersClient,
            CandidateDeltaEventRepository candidateDeltaEventRepository
    ) {
        return new CandidateDeltaService(
                sapClient,
                candidateMapper,
                candidateValidator,
                smartRecruitersClient,
                candidateDeltaEventRepository
        );
    }

    @Bean
    public CandidateBatchMigrationService candidateBatchMigrationService(
            SapClient sapClient,
            CandidateMigrationService candidateMigrationService,
            TenantMigrationJobRepository tenantMigrationJobRepository,
            MigrationProperties properties
    ) {
        return new CandidateBatchMigrationService(
                sapClient,
                candidateMigrationService,
                tenantMigrationJobRepository,
                properties.batchSize(),
                properties.parallelism()
        );
    }

    @Bean(destroyMethod = "close")
    public TenantMigrationJobLauncher tenantMigrationJobLauncher(
            CandidateBatchMigrationService candidateBatchMigrationService,
            MigrationProperties properties
    ) {
        return new TenantMigrationJobLauncher(
                candidateBatchMigrationService,
                properties.maxConcurrentJobs(),
                properties.jobQueueCapacity(),
                properties.jobRecoveryInterval()
        );
    }

    /**
     * The recovery scan starts only once the application is ready, i.e. after Flyway migrated the schema.
     */
    @Bean
    public ApplicationListener<ApplicationReadyEvent> tenantMigrationJobRecoveryStarter(
            TenantMigrationJobLauncher tenantMigrationJobLauncher
    ) {
        return event -> tenantMigrationJobLauncher.start();
    }
}
