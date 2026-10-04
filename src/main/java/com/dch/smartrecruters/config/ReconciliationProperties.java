package com.dch.smartrecruters.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * @param pageSize          candidates per SAP / SmartRecruiters page during a scan
 * @param maxConcurrentRuns reconciliation runs executed at the same time in one instance
 * @param queueCapacity     started runs waiting for a free run thread in one instance
 * @param leaseTimeout      RUNNING run without progress (heartbeat per page) for this long is failed
 * @param recoveryInterval  how often abandoned runs are failed and PENDING runs are started
 */
@ConfigurationProperties(prefix = "migration.reconciliation")
public record ReconciliationProperties(
        @DefaultValue("100") int pageSize,
        @DefaultValue("1") int maxConcurrentRuns,
        @DefaultValue("100") int queueCapacity,
        @DefaultValue("10m") Duration leaseTimeout,
        @DefaultValue("1m") Duration recoveryInterval
) {
}
