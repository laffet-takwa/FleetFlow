package com.fleetflow.delivery.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

import java.lang.reflect.Method;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.fleetflow.common.event.DomainEventPublisher;
import com.fleetflow.common.event.EventEnvelope;
import com.fleetflow.common.event.EventPublishException;
import com.fleetflow.common.event.EventTypes;
import com.fleetflow.common.event.payload.DeliveryStatusChangedPayload;

@ExtendWith(MockitoExtension.class)
@DisplayName("DeliveryEventRelay")
class DeliveryEventRelayTest {

    @Mock
    private DomainEventPublisher eventPublisher;

    @Captor
    private ArgumentCaptor<EventEnvelope<Object>> envelopeCaptor;

    @Test
    @DisplayName("forwards the recorded intent to the bus verbatim")
    void forwardsTheIntentVerbatim() {
        EventEnvelope<?> envelope = envelope();
        OutboundDeliveryEvent intent = new OutboundDeliveryEvent("delivery.completed", "42", envelope);

        new DeliveryEventRelay(eventPublisher).onCommitted(intent);

        verify(eventPublisher).publish(eq("delivery.completed"), eq("42"), envelopeCaptor.capture());
        assertThat(envelopeCaptor.getValue()).isSameAs(envelope);
    }

    @Test
    @DisplayName("sends only after the commit, never while the transaction is still open")
    void runsAfterCommit() throws NoSuchMethodException {
        Method listener = DeliveryEventRelay.class.getMethod("onCommitted", OutboundDeliveryEvent.class);

        TransactionalEventListener annotation = listener.getAnnotation(TransactionalEventListener.class);

        // Publishing from inside the transaction is the defect this relay exists to remove:
        // a rollback would leave the event on the topic with nothing behind it downstream.
        assertThat(annotation).isNotNull();
        assertThat(annotation.phase()).isEqualTo(TransactionPhase.AFTER_COMMIT);
    }

    @Test
    @DisplayName("does not swallow a broker failure, which now happens after the commit")
    void propagatesABrokerFailure() {
        doThrow(new EventPublishException("broker down")).when(eventPublisher)
                .publish(anyString(), anyString(), any());

        DeliveryEventRelay relay = new DeliveryEventRelay(eventPublisher);
        OutboundDeliveryEvent intent = new OutboundDeliveryEvent("delivery.completed", "42", envelope());

        assertThatThrownBy(() -> relay.onCommitted(intent)).isInstanceOf(EventPublishException.class);
    }

    private static EventEnvelope<?> envelope() {
        return EventEnvelope.of(EventTypes.DELIVERY_COMPLETED, "delivery.completed",
                new DeliveryStatusChangedPayload(12L, 42L, 8L, 3L, 33L, "IN_TRANSIT", "DELIVERED", null));
    }
}