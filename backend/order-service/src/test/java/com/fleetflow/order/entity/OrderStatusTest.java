package com.fleetflow.order.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import com.fleetflow.common.exception.InvalidStateTransitionException;

/**
 * The status machine is the single most safety critical piece of the service: a
 * permissive table here would let a delivered order be cancelled, so the table itself
 * is asserted pair by pair rather than only through the service.
 */
@DisplayName("OrderStatus transition table")
class OrderStatusTest {

    static List<Arguments> allowedPairs() {
        return List.of(
                Arguments.of(OrderStatus.CREATED, OrderStatus.CONFIRMED),
                Arguments.of(OrderStatus.CREATED, OrderStatus.CANCELLED),
                Arguments.of(OrderStatus.CONFIRMED, OrderStatus.PROCESSING),
                Arguments.of(OrderStatus.CONFIRMED, OrderStatus.CANCELLED),
                Arguments.of(OrderStatus.PROCESSING, OrderStatus.READY_FOR_DELIVERY),
                Arguments.of(OrderStatus.PROCESSING, OrderStatus.CANCELLED),
                Arguments.of(OrderStatus.READY_FOR_DELIVERY, OrderStatus.OUT_FOR_DELIVERY),
                Arguments.of(OrderStatus.OUT_FOR_DELIVERY, OrderStatus.DELIVERED));
    }

    @ParameterizedTest(name = "{0} -> {1} is allowed")
    @MethodSource("allowedPairs")
    void allowsDocumentedTransitions(OrderStatus from, OrderStatus to) {
        assertTrue(from.canTransitionTo(to), () -> from + " -> " + to + " should be allowed");
    }

    @Test
    @DisplayName("the target set of every status is exactly the published table")
    void targetSetsMatchTheContract() {
        assertEquals(Set.of(OrderStatus.CONFIRMED, OrderStatus.CANCELLED),
                OrderStatus.CREATED.allowedTargets());
        assertEquals(Set.of(OrderStatus.PROCESSING, OrderStatus.CANCELLED),
                OrderStatus.CONFIRMED.allowedTargets());
        assertEquals(Set.of(OrderStatus.READY_FOR_DELIVERY, OrderStatus.CANCELLED),
                OrderStatus.PROCESSING.allowedTargets());
        assertEquals(Set.of(OrderStatus.OUT_FOR_DELIVERY), OrderStatus.READY_FOR_DELIVERY.allowedTargets());
        assertEquals(Set.of(OrderStatus.DELIVERED), OrderStatus.OUT_FOR_DELIVERY.allowedTargets());
        assertEquals(Set.of(), OrderStatus.DELIVERED.allowedTargets());
        assertEquals(Set.of(), OrderStatus.CANCELLED.allowedTargets());
    }

    @Nested
    @DisplayName("refused transitions")
    class Refused {

        @Test
        @DisplayName("DELIVERED is terminal")
        void deliveredIsTerminal() {
            assertTrue(OrderStatus.DELIVERED.isTerminal());
            assertTrue(OrderStatus.DELIVERED.allowedTargets().isEmpty());
            for (OrderStatus target : OrderStatus.values()) {
                assertFalse(OrderStatus.DELIVERED.canTransitionTo(target),
                        () -> "DELIVERED -> " + target + " must be refused");
            }
        }

        @Test
        @DisplayName("CANCELLED is terminal")
        void cancelledIsTerminal() {
            assertTrue(OrderStatus.CANCELLED.isTerminal());
            for (OrderStatus target : OrderStatus.values()) {
                assertFalse(OrderStatus.CANCELLED.canTransitionTo(target),
                        () -> "CANCELLED -> " + target + " must be refused");
            }
        }

        @Test
        @DisplayName("an order can never move backwards")
        void neverMovesBackwards() {
            assertFalse(OrderStatus.PROCESSING.canTransitionTo(OrderStatus.CREATED));
            assertFalse(OrderStatus.OUT_FOR_DELIVERY.canTransitionTo(OrderStatus.PROCESSING));
            assertFalse(OrderStatus.READY_FOR_DELIVERY.canTransitionTo(OrderStatus.PROCESSING));
        }

        @Test
        @DisplayName("skipping states is refused")
        void refusesSkippedStates() {
            assertFalse(OrderStatus.CREATED.canTransitionTo(OrderStatus.PROCESSING));
            assertFalse(OrderStatus.CREATED.canTransitionTo(OrderStatus.DELIVERED));
            assertFalse(OrderStatus.CONFIRMED.canTransitionTo(OrderStatus.OUT_FOR_DELIVERY));
            assertFalse(OrderStatus.PROCESSING.canTransitionTo(OrderStatus.OUT_FOR_DELIVERY));
        }

        @Test
        @DisplayName("a self transition is not a transition")
        void refusesSelfTransition() {
            for (OrderStatus status : OrderStatus.values()) {
                assertFalse(status.canTransitionTo(status), () -> status + " -> itself must be refused");
            }
        }

        @Test
        @DisplayName("a null target is refused rather than throwing")
        void refusesNullTarget() {
            for (OrderStatus status : OrderStatus.values()) {
                assertFalse(status.canTransitionTo(null));
            }
        }
    }

    @Test
    @DisplayName("CREATED is the only entry point and every other status is reachable")
    void everyStatusExceptCreatedIsReachable() {
        List<OrderStatus> entryPoints = java.util.Arrays.stream(OrderStatus.values())
                .filter(status -> java.util.Arrays.stream(OrderStatus.values())
                        .noneMatch(candidate -> candidate.canTransitionTo(status)))
                .toList();

        assertEquals(List.of(OrderStatus.CREATED), entryPoints);
    }

    @Test
    @DisplayName("the exception message names the aggregate, the id and both states")
    void exceptionIsDiagnostic() {
        InvalidStateTransitionException ex = assertThrows(InvalidStateTransitionException.class,
                () -> {
                    throw new InvalidStateTransitionException("Order", 42L, "DELIVERED", "CANCELLED");
                });
        assertEquals("Order 42 cannot move from DELIVERED to CANCELLED", ex.getMessage());
    }
}
