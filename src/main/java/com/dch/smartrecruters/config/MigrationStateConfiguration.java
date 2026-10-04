package com.dch.smartrecruters.config;

import com.dch.smartrecruters.state.CandidateDeltaEventRepository;
import com.dch.smartrecruters.state.MigrationRecordRepository;
import com.dch.smartrecruters.state.TenantMigrationJobRepository;
import com.dch.smartrecruters.state.jdbc.JdbcCandidateDeltaEventRepository;
import com.dch.smartrecruters.state.jdbc.JdbcMigrationRecordRepository;
import com.dch.smartrecruters.state.jdbc.JdbcTenantMigrationJobRepository;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

@Configuration
@EnableConfigurationProperties(MigrationProperties.class)
public class MigrationStateConfiguration {

    @Bean
    public MigrationRecordRepository migrationRecordRepository(
            DataSource dataSource,
            MigrationProperties properties
    ) {
        return new JdbcMigrationRecordRepository(dataSource, properties.claimTimeout());
    }

    @Bean
    public TenantMigrationJobRepository tenantMigrationJobRepository(
            DataSource dataSource,
            MigrationProperties properties
    ) {
        return new JdbcTenantMigrationJobRepository(dataSource, properties.jobLeaseTimeout());
    }

    @Bean
    public CandidateDeltaEventRepository candidateDeltaEventRepository(
            DataSource dataSource,
            MigrationProperties properties
    ) {
        return new JdbcCandidateDeltaEventRepository(dataSource, properties.claimTimeout());
    }
}
