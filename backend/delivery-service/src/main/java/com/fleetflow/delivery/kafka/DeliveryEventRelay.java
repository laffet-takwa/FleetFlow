package com.fleetflow.delivery.kafka;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.fleetflow.common.correlation.CorrelationId;
import com.fleetflow.common.event.DomainEventPublisher;

/**
 * Sends a recorded {@link OutboundDeliveryEvent} to the bus, but only once the transaction
 * that produced it has committed.
 *
 * <p>Publishing inline would let a rolled back transition still be acted on downstream:
 * the event is already on the topic while the row that justified it is gone. Registering
 * an intent inside the transaction and releasing it after the commit closes that window.
 */
@Component
public class DeliveryEventRelay {

    private static final Logger log = LoggerFactory.getLogger(DeliveryEventRelay.class);

    private final DomainEventPublisher eventPublisher;

    public DeliveryEventRelay(DomainEventPublisher eventPublisher) {
        this.eventPublisher = eventPublisher;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onCommitted(OutboundDeliveryEvent event) {
        log.info("Publishing {} for key {} after commit [correlationId={}]", event.envelope().eventType(),
                event.key(), CorrelationId.getOrCreate());
        eventPublisher.publish(event.topic(), event.key(), event.envelope());
    }
}