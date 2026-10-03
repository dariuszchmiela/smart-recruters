package com.dch.smartrecruters.config;

import com.dch.smartrecruters.client.SapClient;
import com.dch.smartrecruters.client.SmartRecruitersClient;
import com.dch.smartrecruters.mapper.CandidateMapper;
import com.dch.smartrecruters.service.CandidateMigrationService;
import com.dch.smartrecruters.state.MigrationRecordRepository;
import com.dch.smartrecruters.validation.CandidateValidator;
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
}
