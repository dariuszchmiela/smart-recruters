package com.dch.smartrecruters.config;

import com.dch.smartrecruters.state.MigrationRecordRepository;
import com.dch.smartrecruters.state.jdbc.JdbcMigrationRecordRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

@Configuration
public class MigrationStateConfiguration {

    @Bean
    public MigrationRecordRepository migrationRecordRepository(DataSource dataSource) {
        return new JdbcMigrationRecordRepository(dataSource);
    }
}
