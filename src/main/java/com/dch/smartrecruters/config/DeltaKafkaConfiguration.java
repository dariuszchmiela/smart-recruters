package com.dch.smartrecruters.config;

import com.dch.smartrecruters.messaging.CandidateChangedEvent;
import com.dch.smartrecruters.service.PermanentDeltaEventException;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.Serializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;
import org.springframework.kafka.support.serializer.DelegatingByTypeSerializer;
import org.springframework.kafka.support.serializer.JacksonJsonSerializer;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Kafka retry policy for delta events, separate from the HTTP retry inside one processing attempt:
 * <pre>
 * delivery 1 -> (HTTP retry inside) -> fails -> back off -> delivery 2 -> ... -> delivery maxAttempts
 *   -> dead letter topic
 * PermanentDeltaEventException / deserialization error -> dead letter topic right away
 * </pre>
 * Retries are blocking (the record is re-sought on its partition), so later changes of the same
 * candidate - same partition key - are never applied before an earlier one is finished.
 */
@Configuration
@EnableConfigurationProperties(DeltaKafkaProperties.class)
public class DeltaKafkaConfiguration {

    @Bean
    public NewTopic candidateChangesTopic(DeltaKafkaProperties properties) {
        return TopicBuilder.name(properties.candidateChangesTopic())
                .partitions(properties.partitions())
                .build();
    }

    @Bean
    public NewTopic candidateChangesDltTopic(DeltaKafkaProperties properties) {
        return TopicBuilder.name(properties.candidateChangesDltTopic())
                .partitions(properties.partitions())
                .build();
    }

    /**
     * Publishes dead letters. Values are the deserialized event, or the raw bytes when the
     * payload could not be deserialized at all, hence the serializer per type.
     */
    @Bean
    public KafkaTemplate<Object, Object> deadLetterKafkaTemplate(KafkaProperties kafkaProperties) {
        Map<Class<?>, Serializer<?>> valueSerializers = new LinkedHashMap<>();
        valueSerializers.put(byte[].class, new ByteArraySerializer());
        valueSerializers.put(CandidateChangedEvent.class, new JacksonJsonSerializer<>().noTypeInfo());

        @SuppressWarnings({"unchecked", "rawtypes"})
        DefaultKafkaProducerFactory<Object, Object> producerFactory = new DefaultKafkaProducerFactory<>(
                kafkaProperties.buildProducerProperties(),
                (Serializer) new StringSerializer(),
                new DelegatingByTypeSerializer(valueSerializers)
        );
        return new KafkaTemplate<>(producerFactory);
    }

    /**
     * Picked up by Spring Boot for the default listener container factory.
     */
    @Bean
    public DefaultErrorHandler candidateChangesErrorHandler(
            KafkaOperations<Object, Object> deadLetterKafkaTemplate,
            DeltaKafkaProperties properties
    ) {
        // partition -1: let the producer choose, the DLT may have a different partition count
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(
                deadLetterKafkaTemplate,
                (record, exception) -> new TopicPartition(properties.candidateChangesDltTopic(), -1)
        );

        ExponentialBackOffWithMaxRetries backOff = new ExponentialBackOffWithMaxRetries(properties.maxAttempts() - 1);
        backOff.setInitialInterval(properties.retryInitialBackoff().toMillis());
        backOff.setMultiplier(properties.retryMultiplier());
        backOff.setMaxInterval(properties.retryMaxBackoff().toMillis());

        DefaultErrorHandler errorHandler = new DefaultErrorHandler(recoverer, backOff);
        // cause chain is inspected, so the wrapped listener exception is classified correctly
        errorHandler.addNotRetryableExceptions(PermanentDeltaEventException.class);
        return errorHandler;
    }
}
