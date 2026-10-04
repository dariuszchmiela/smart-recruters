package com.dch.smartrecruters.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * @param candidateChangesTopic    topic with {@code CandidateChangedEvent}s
 * @param candidateChangesDltTopic dead letter topic for events that failed all attempts or failed permanently
 * @param partitions               partitions of both topics when they are created by the application
 * @param maxAttempts              deliveries of one event in total (first attempt + Kafka retries)
 * @param retryInitialBackoff      wait before the first Kafka retry
 * @param retryMultiplier          growth of the wait between Kafka retries
 * @param retryMaxBackoff          upper bound of the wait between Kafka retries
 */
@ConfigurationProperties(prefix = "migration.kafka")
public record DeltaKafkaProperties(
        @DefaultValue("candidate-changes") String candidateChangesTopic,
        @DefaultValue("candidate-changes.DLT") String candidateChangesDltTopic,
        @DefaultValue("3") int partitions,
        @DefaultValue("3") int maxAttempts,
        @DefaultValue("1s") Duration retryInitialBackoff,
        @DefaultValue("2.0") double retryMultiplier,
        @DefaultValue("10s") Duration retryMaxBackoff
) {

    public DeltaKafkaProperties {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("migration.kafka.max-attempts must be >= 1");
        }
    }
}
