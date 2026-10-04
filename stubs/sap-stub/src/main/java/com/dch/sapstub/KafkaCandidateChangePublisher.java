package com.dch.sapstub;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Component
public class KafkaCandidateChangePublisher implements CandidateChangePublisher {

    private final KafkaTemplate<String, CandidateChangedEvent> kafkaTemplate;
    private final String topic;

    public KafkaCandidateChangePublisher(
            KafkaTemplate<String, CandidateChangedEvent> kafkaTemplate,
            @Value("${sap-stub.candidate-changes-topic}") String topic
    ) {
        this.kafkaTemplate = kafkaTemplate;
        this.topic = topic;
    }

    /**
     * Key tenantId:candidateId keeps all changes of one candidate on one partition, in order.
     * Waits for the broker acknowledgement so the caller knows whether the event was published.
     */
    @Override
    public void publish(CandidateChangedEvent event) {
        try {
            kafkaTemplate.send(topic, event.tenantId() + ":" + event.candidateId(), event)
                    .get(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while publishing " + event.eventId(), e);
        } catch (ExecutionException | TimeoutException e) {
            throw new IllegalStateException("Cannot publish " + event.eventId(), e);
        }
    }
}
