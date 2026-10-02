package com.fleetflow.common.event;

/** Publishes a domain event onto the FleetFlow event bus. */
public interface DomainEventPublisher {

    /**
     * @param topic    target topic, see {@link KafkaTopics}
     * @param key      partition key, usually the aggregate id to preserve ordering
     * @param envelope event to publish, already carrying a generated event id
     */
    <T> void publish(String topic, String key, EventEnvelope<T> envelope);
}