package com.fleetflow.delivery.kafka;

import com.fleetflow.common.event.EventEnvelope;

/**
 * A domain event the service intends to publish, recorded inside the transaction that
 * changes state and handed to {@link DeliveryEventRelay} once that transaction commits.
 *
 * <p>The envelope is built eagerly, while the correlation id of the originating request is
 * still on the thread, so moving the actual send after the commit does not lose it.
 *
 * @param topic    target topic, see {@code fleetflow.kafka.topics.*}
 * @param key      partition key, the order id, so one order's events stay ordered
 * @param envelope the event itself
 */
public record OutboundDeliveryEvent(String topic, String key, EventEnvelope<?> envelope) {
}