package com.dch.smartrecruters.messaging;

import com.dch.smartrecruters.client.ExternalSystemException;
import com.dch.smartrecruters.client.FailureType;
import com.dch.smartrecruters.config.DeltaKafkaConfiguration;
import com.dch.smartrecruters.service.CandidateDeltaService;
import com.dch.smartrecruters.service.DeltaEventLeaseLostException;
import com.dch.smartrecruters.service.PermanentDeltaEventException;
import com.dch.smartrecruters.validation.CandidateValidationException;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.client.ResourceAccessException;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Real broker (Testcontainers), real listener container, retry and dead letter configuration,
 * real consumer settings from application.yaml. Only the processing service is mocked.
 * Skipped when no Docker-compatible runtime is available.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(
        classes = {DeltaKafkaConfiguration.class, CandidateChangedListener.class},
        properties = {
                "migration.kafka.candidate-changes-topic=it-candidate-changes",
                "migration.kafka.candidate-changes-dlt-topic=it-candidate-changes.DLT",
                "migration.kafka.max-attempts=3",
                "migration.kafka.retry-initial-backoff=100ms",
                "migration.kafka.retry-max-backoff=200ms",
                "spring.kafka.consumer.group-id=it-migration-service"
        }
)
@ImportAutoConfiguration(KafkaAutoConfiguration.class)
class CandidateChangedListenerKafkaIntegrationTest {

    private static final Duration WAIT = Duration.ofSeconds(30);
    private static final String TENANT = "tenant-1";

    @Container
    private static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka-native:4.1.0");

    private static final List<ConsumerRecord<String, String>> deadLetters = new CopyOnWriteArrayList<>();
    private static KafkaProducer<String, String> producer;

    @MockitoBean
    private CandidateDeltaService deltaService;

    @Value("${migration.kafka.candidate-changes-topic}")
    private String topic;

    @Value("${migration.kafka.candidate-changes-dlt-topic}")
    private String dltTopic;

    @DynamicPropertySource
    static void kafkaProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    }

    @BeforeAll
    static void createProducer() {
        producer = new KafkaProducer<>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class
        ));
    }

    @AfterAll
    static void closeProducer() {
        producer.close();
    }

    @Test
    void shouldDeliverPlainJsonEventToDeltaServiceOnce() throws Exception {
        UUID eventId = UUID.randomUUID();
        String candidateId = "candidate-ok-" + eventId;

        send(candidateId, json(eventId, candidateId));

        CandidateChangedEvent expected =
                new CandidateChangedEvent(eventId, TENANT, candidateId, Instant.parse("2026-01-01T10:00:00Z"));
        verify(deltaService, timeout(WAIT.toMillis())).process(expected);
        // success is not retried
        verify(deltaService, after(1_000).times(1)).process(expected);
    }

    @Test
    void shouldSendPermanentFailureToDeadLetterTopicWithoutRetry() throws Exception {
        UUID eventId = UUID.randomUUID();
        String candidateId = "candidate-permanent-" + eventId;
        doThrow(new PermanentDeltaEventException("Candidate email is required"))
                .when(deltaService).process(argThat(event -> event != null && candidateId.equals(event.candidateId())));

        send(candidateId, json(eventId, candidateId));

        ConsumerRecord<String, String> deadLetter = awaitDeadLetter(key(candidateId));
        assertTrue(deadLetter.value().contains(eventId.toString()));
        assertEquals(PermanentDeltaEventException.class.getName(), header(deadLetter, "kafka_dlt-exception-cause-fqcn"));
        assertEquals(topic, header(deadLetter, "kafka_dlt-original-topic"));
        verify(deltaService, times(1)).process(argThat(event -> event != null && candidateId.equals(event.candidateId())));
    }

    @Test
    void shouldRetryTransientFailureBoundedTimesThenDeadLetter() throws Exception {
        UUID eventId = UUID.randomUUID();
        String candidateId = "candidate-transient-" + eventId;
        doThrow(ExternalSystemException.fromHttpClientFailure("SmartRecruiters PUT candidate", FailureType.TRANSIENT,
                new ResourceAccessException("Read timed out")))
                .when(deltaService).process(argThat(event -> event != null && candidateId.equals(event.candidateId())));

        send(candidateId, json(eventId, candidateId));

        ConsumerRecord<String, String> deadLetter = awaitDeadLetter(key(candidateId));
        assertEquals(ExternalSystemException.class.getName(), header(deadLetter, "kafka_dlt-exception-cause-fqcn"));
        // first delivery + 2 Kafka retries (max-attempts=3), then no more
        verify(deltaService, after(1_000).times(3))
                .process(argThat(event -> event != null && candidateId.equals(event.candidateId())));
    }

    @Test
    void shouldSendCandidateValidationFailureToDeadLetterTopicWithoutRetry() throws Exception {
        UUID eventId = UUID.randomUUID();
        String candidateId = "candidate-invalid-" + eventId;
        // exactly what CandidateDeltaService throws for a candidate failing validation
        doThrow(new PermanentDeltaEventException("Invalid source data for event " + eventId,
                new CandidateValidationException("Candidate email is required")))
                .when(deltaService).process(argThat(event -> event != null && candidateId.equals(event.candidateId())));

        send(candidateId, json(eventId, candidateId));

        ConsumerRecord<String, String> deadLetter = awaitDeadLetter(key(candidateId));
        assertEquals(PermanentDeltaEventException.class.getName(), header(deadLetter, "kafka_dlt-exception-cause-fqcn"));
        verify(deltaService, after(1_000).times(1))
                .process(argThat(event -> event != null && candidateId.equals(event.candidateId())));
    }

    @Test
    void shouldRetryGenericIllegalArgumentExceptionBoundedTimesThenDeadLetter() throws Exception {
        UUID eventId = UUID.randomUUID();
        String candidateId = "candidate-bug-" + eventId;
        doThrow(new IllegalArgumentException("unexpected mapping state"))
                .when(deltaService).process(argThat(event -> event != null && candidateId.equals(event.candidateId())));

        send(candidateId, json(eventId, candidateId));

        // not a business validation failure: retried like any unknown failure (max-attempts=3), then DLT
        ConsumerRecord<String, String> deadLetter = awaitDeadLetter(key(candidateId));
        assertEquals(IllegalArgumentException.class.getName(), header(deadLetter, "kafka_dlt-exception-cause-fqcn"));
        verify(deltaService, after(1_000).times(3))
                .process(argThat(event -> event != null && candidateId.equals(event.candidateId())));
    }

    @Test
    void shouldRetryLostLeaseEvenWhenStaleAttemptFailedPermanently() throws Exception {
        UUID eventId = UUID.randomUUID();
        String candidateId = "candidate-lease-lost-" + eventId;
        DeltaEventLeaseLostException leaseLost = new DeltaEventLeaseLostException(eventId, "mark it FAILED");
        leaseLost.addSuppressed(new PermanentDeltaEventException("Candidate email is required"));
        doThrow(leaseLost)
                .when(deltaService).process(argThat(event -> event != null && candidateId.equals(event.candidateId())));

        send(candidateId, json(eventId, candidateId));

        // the stale attempt's permanent failure must not short-circuit to the DLT: the event is retried
        ConsumerRecord<String, String> deadLetter = awaitDeadLetter(key(candidateId));
        assertEquals(DeltaEventLeaseLostException.class.getName(), header(deadLetter, "kafka_dlt-exception-cause-fqcn"));
        verify(deltaService, after(1_000).times(3))
                .process(argThat(event -> event != null && candidateId.equals(event.candidateId())));
    }

    @Test
    void shouldSendUnreadablePayloadToDeadLetterTopic() throws Exception {
        String candidateId = "candidate-garbage-" + UUID.randomUUID();
        String garbage = "{not json";

        send(candidateId, garbage);

        ConsumerRecord<String, String> deadLetter = awaitDeadLetter(key(candidateId));
        assertEquals(garbage, deadLetter.value());
        verify(deltaService, times(0)).process(argThat(event -> event == null));
    }

    private void send(String candidateId, String payload) throws ExecutionException, InterruptedException {
        producer.send(new ProducerRecord<>(topic, key(candidateId), payload)).get();
    }

    private ConsumerRecord<String, String> awaitDeadLetter(String key) {
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "it-dlt-reader-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class
        ))) {
            consumer.subscribe(List.of(dltTopic));
            Instant deadline = Instant.now().plus(WAIT);
            while (Instant.now().isBefore(deadline)) {
                consumer.poll(Duration.ofMillis(500)).forEach(deadLetters::add);
                for (ConsumerRecord<String, String> record : deadLetters) {
                    if (key.equals(record.key())) {
                        return record;
                    }
                }
            }
        }
        return fail("No dead letter with key " + key);
    }

    private static String header(ConsumerRecord<String, String> record, String name) {
        Header header = record.headers().lastHeader(name);
        assertNotNull(header, "missing header " + name);
        return new String(header.value(), StandardCharsets.UTF_8);
    }

    private static String key(String candidateId) {
        return TENANT + ":" + candidateId;
    }

    private static String json(UUID eventId, String candidateId) {
        return """
                {"eventId": "%s", "tenantId": "%s", "candidateId": "%s", "occurredAt": "2026-01-01T10:00:00Z"}
                """.formatted(eventId, TENANT, candidateId);
    }
}
