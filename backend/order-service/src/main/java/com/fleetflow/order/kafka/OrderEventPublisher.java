package com.fleetflow.order.kafka;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.fleetflow.common.correlation.CorrelationId;
import com.fleetflow.common.event.DomainEventPublisher;
import com.fleetflow.common.event.EventEnvelope;

/**
 * Puts a committed order onto the event bus.
 *
 * <p>The service records an {@link Intent} and this listener sends it once the transaction
 * has committed. Publishing from inside the transaction instead would announce an order
 * that a later failure rolls back, and the warehouse would then reserve stock for an
 * order that does not exist.
 */
@Component
public class OrderEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(OrderEventPublisher.class);

    /**
     * What a placed order decided needs to go on the bus, recorded before the commit.
     *
     * @param topic     target topic, bound from {@code fleetflow.kafka.topics}
     * @param orderId   partition key, so one order's events keep their order
     * @param eventType logical event name, see {@link com.fleetflow.common.event.EventTypes}
     * @param payload   the {@code order.created} body
     */
    public record Intent(String topic, Long orderId, String eventType, Object payload) {
    }

    private final DomainEventPublisher eventPublisher;

    public OrderEventPublisher(DomainEventPublisher eventPublisher) {
        this.eventPublisher = eventPublisher;
    }

    /**
     * {@code fallbackExecution} keeps the handler honest when it is called with no
     * transaction in progress, which is what plain unit tests do.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onIntent(Intent intent) {
        try {
            eventPublisher.publish(intent.topic(), String.valueOf(intent.orderId()),
                    EventEnvelope.of(intent.eventType(), intent.topic(), intent.payload()));
            log.info("Published {} for order {} to {} [correlationId={}]", intent.eventType(), intent.orderId(),
                    intent.topic(), CorrelationId.getOrCreate());
        } catch (RuntimeException ex) {
            // The order is already committed at this point, so rethrowing could only turn
            // a placed order into a failed response and invite a duplicate submission.
            // The loss is recorded loudly instead, for an operator to replay.
            log.error("Could not deliver {} for order {} to {}; the order itself is committed "
                    + "[correlationId={}]", intent.eventType(), intent.orderId(), intent.topic(),
                    CorrelationId.getOrCreate(), ex);
        }
    }
}