package com.fleetflow.common.event;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import org.springframework.kafka.core.KafkaTemplate;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import lombok.extern.slf4j.Slf4j;

import com.fleetflow.common.correlation.CorrelationId;

/**
 * Kafka backed {@link DomainEventPublisher}.
 *
 * <p>The envelope is serialised to plain JSON by an explicit {@link ObjectMapper}
 * rather than by Jackson's reflective Kafka serializers. That keeps the wire format
 * completely predictable - it matches {@code docs/kafka-events.md} byte for byte -
 * and removes the need for trusted package configuration on either side of the bus.
 *
 * <p>Publishing is synchronous on purpose: the producing service has already
 * committed its own transaction, so a broker outage must surface as a failed API
 * response rather than a silently dropped event.
 */
@Slf4j
public class KafkaDomainEventPublisher implements DomainEventPublisher {

    private static final long PUBLISH_TIMEOUT_SECONDS = 10L;

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    public KafkaDomainEventPublisher(KafkaTemplate<String, String> kafkaTemplate, ObjectMapper objectMapper) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper.copy()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    @Override
    public <T> void publish(String topic, String key, EventEnvelope<T> envelope) {
        try {
            String json = objectMapper.writeValueAsString(envelope);
            kafkaTemplate.send(topic, key, json).get(PUBLISH_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            log.debug("Published {} [{}] to topic {} with correlationId {}",
                    envelope.eventType(), envelope.eventId(), topic, envelope.correlationId());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new EventPublishException("Interrupted while publishing " + envelope.eventType() + " to " + topic, ex);
        } catch (EventPublishException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new EventPublishException("Failed to publish " + envelope.eventType() + " to " + topic, ex);
        }
    }

    /** Copies the correlation id into a record header so consumers can log the same id. */
    public static byte[] correlationHeaderBytes() {
        return CorrelationId.getOrCreate().getBytes(StandardCharsets.UTF_8);
    }
}