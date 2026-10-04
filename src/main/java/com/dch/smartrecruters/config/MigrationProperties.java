package com.dch.smartrecruters.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * @param claimTimeout        IN_PROGRESS candidate older than this is treated as abandoned
 * @param batchSize           candidates per SAP page (fixed per job once the job is created)
 * @param parallelism         maximum candidates migrated at the same time within one job
 * @param maxConcurrentJobs   tenant jobs running at the same time in one instance
 * @param jobQueueCapacity    started jobs waiting for a free job thread in one instance
 * @param jobLeaseTimeout     RUNNING job not updated for this long is treated as abandoned
 * @param jobRecoveryInterval how often PENDING and abandoned jobs are looked up and resumed
 */
@ConfigurationProperties(prefix = "migration")
public record MigrationProperties(
        Duration claimTimeout,
        @DefaultValue("100") int batchSize,
        @DefaultValue("8") int parallelism,
        @DefaultValue("2") int maxConcurrentJobs,
        @DefaultValue("100") int jobQueueCapacity,
        @DefaultValue("10m") Duration jobLeaseTimeout,
        @DefaultValue("1m") Duration jobRecoveryInterval
) {

    public MigrationProperties {
        // a resumed job must find the candidates of the crashed run reclaimable, not "fresh IN_PROGRESS"
        if (claimTimeout != null && jobLeaseTimeout != null && jobLeaseTimeout.compareTo(claimTimeout) < 0) {
            throw new IllegalArgumentException("migration.job-lease-timeout must be >= migration.claim-timeout");
        }
    }
}
