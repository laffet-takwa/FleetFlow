package com.fleetflow.delivery.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.fleetflow.common.exception.BusinessException;
import com.fleetflow.common.exception.ErrorCode;
import com.fleetflow.common.exception.InvalidStateTransitionException;

@DisplayName("DeliveryStatus")
class DeliveryStatusTest {

    private static final Map<DeliveryStatus, Set<DeliveryStatus>> ALLOWED = Map.of(
            DeliveryStatus.CREATED, Set.of(DeliveryStatus.ASSIGNED, DeliveryStatus.CANCELLED),
            DeliveryStatus.ASSIGNED, Set.of(DeliveryStatus.PICKED_UP, DeliveryStatus.CANCELLED),
            DeliveryStatus.PICKED_UP, Set.of(DeliveryStatus.IN_TRANSIT, DeliveryStatus.FAILED),
            DeliveryStatus.IN_TRANSIT, Set.of(DeliveryStatus.DELIVERED, DeliveryStatus.FAILED),
            DeliveryStatus.FAILED, Set.of(DeliveryStatus.ASSIGNED),
            DeliveryStatus.DELIVERED, Set.of(),
            DeliveryStatus.CANCELLED, Set.of());

    @ParameterizedTest(name = "{0} may move to {1}")
    @CsvSource({
            "CREATED,ASSIGNED",
            "CREATED,CANCELLED",
            "ASSIGNED,PICKED_UP",
            "ASSIGNED,CANCELLED",
            "PICKED_UP,IN_TRANSIT",
            "PICKED_UP,FAILED",
            "IN_TRANSIT,DELIVERED",
            "IN_TRANSIT,FAILED",
            "FAILED,ASSIGNED"
    })
    void allowsTheDocumentedTransitions(DeliveryStatus from, DeliveryStatus to) {
        assertThat(from.canTransitionTo(to)).isTrue();
    }

    @Nested
    @DisplayName("refused transitions")
    class Refused {

        @ParameterizedTest(name = "{0} may not move to {1}")
        @CsvSource({
                "CREATED,PICKED_UP",
                "CREATED,IN_TRANSIT",
                "CREATED,DELIVERED",
                "CREATED,FAILED",
                "ASSIGNED,IN_TRANSIT",
                "ASSIGNED,DELIVERED",
                "ASSIGNED,FAILED",
                "PICKED_UP,DELIVERED",
                "PICKED_UP,CANCELLED",
                "IN_TRANSIT,PICKED_UP",
                "IN_TRANSIT,CANCELLED",
                "DELIVERED,IN_TRANSIT",
                "DELIVERED,ASSIGNED",
                "DELIVERED,FAILED",
                "DELIVERED,CANCELLED",
                "CANCELLED,ASSIGNED",
                "CANCELLED,IN_TRANSIT",
                "CANCELLED,DELIVERED",
                "FAILED,DELIVERED",
                "FAILED,CANCELLED",
                "FAILED,PICKED_UP",
                "FAILED,IN_TRANSIT"
        })
        void rejectsEverythingElse(DeliveryStatus from, DeliveryStatus to) {
            assertThat(from.canTransitionTo(to)).isFalse();
        }

        @Test
        @DisplayName("DELIVERED and CANCELLED are terminal")
        void terminalStatesHaveNoExit() {
            for (DeliveryStatus target : DeliveryStatus.values()) {
                assertThat(DeliveryStatus.DELIVERED.canTransitionTo(target)).isFalse();
                assertThat(DeliveryStatus.CANCELLED.canTransitionTo(target)).isFalse();
            }
        }

        @Test
        @DisplayName("a null target is refused rather than silently accepted")
        void nullTargetIsRefused() {
            for (DeliveryStatus from : DeliveryStatus.values()) {
                assertThat(from.canTransitionTo(null)).isFalse();
            }
        }
    }

    @Test
    @DisplayName("the transition table has no undocumented edge")
    void tableIsExactlyWhatIsDocumented() {
        for (DeliveryStatus from : DeliveryStatus.values()) {
            for (DeliveryStatus to : DeliveryStatus.values()) {
                assertThat(from.canTransitionTo(to))
                        .as("%s -> %s", from, to)
                        .isEqualTo(ALLOWED.get(from).contains(to));
            }
        }
    }

    @Test
    @DisplayName("only CREATED, ASSIGNED, PICKED_UP and IN_TRANSIT keep a driver and a vehicle busy")
    void openStatesAreTheNonTerminalOnes() {
        assertThat(DeliveryStatus.OPEN)
                .containsExactlyInAnyOrder(DeliveryStatus.CREATED, DeliveryStatus.ASSIGNED,
                        DeliveryStatus.PICKED_UP, DeliveryStatus.IN_TRANSIT);
        assertThat(DeliveryStatus.DELIVERED.isTerminal()).isTrue();
        assertThat(DeliveryStatus.CANCELLED.isTerminal()).isTrue();
        assertThat(DeliveryStatus.FAILED.isTerminal()).isTrue();
        assertThat(DeliveryStatus.CREATED.isTerminal()).isFalse();
    }

    @ParameterizedTest
    @EnumSource(DeliveryStatus.class)
    @DisplayName("parsing is case insensitive and trims")
    void parsesLooselyTypedValues(DeliveryStatus status) {
        assertThat(DeliveryStatus.from(" " + status.name().toLowerCase() + " ")).isEqualTo(status);
    }

    @ParameterizedTest
    @ValueSource(strings = { "TELEPORTED", "", "  " })
    @DisplayName("an unknown status is a 400, not a silent null")
    void rejectsUnknownValues(String value) {
        assertThatThrownBy(() -> DeliveryStatus.from(value))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode()).isEqualTo(ErrorCode.BAD_REQUEST));
    }

    @Test
    @DisplayName("the entity refuses DELIVERED to IN_TRANSIT with an InvalidStateTransitionException")
    void entityThrowsOnIllegalTransition() {
        Delivery delivery = new Delivery();
        delivery.setId(12L);
        delivery.setStatus(DeliveryStatus.DELIVERED);

        try {
            delivery.transitionTo(DeliveryStatus.IN_TRANSIT);
        } catch (InvalidStateTransitionException ex) {
            assertThat(ex.getMessage()).contains("Delivery 12").contains("DELIVERED").contains("IN_TRANSIT");
            return;
        }
        throw new AssertionError("expected an InvalidStateTransitionException");
    }
}
