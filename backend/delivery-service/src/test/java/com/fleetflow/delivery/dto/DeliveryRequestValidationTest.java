package com.fleetflow.delivery.dto;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import jakarta.validation.Validation;
import jakarta.validation.Validator;

/**
 * The reason a caller supplies is stored in {@code deliveries.failure_reason} and
 * {@code deliveries.proof_of_delivery}, which are {@code VARCHAR(255)}. Without the bound
 * below an over-long string reaches the database and comes back as a constraint violation
 * reported as a conflict, when what the caller got wrong is a field length.
 */
@DisplayName("Delivery request validation")
class DeliveryRequestValidationTest {

    private static final String COLUMN_WIDTH = "a".repeat(255);

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    @DisplayName("a reason up to the column width is accepted")
    void acceptsAReasonThatFits() {
        assertThat(validator.validate(new UpdateDeliveryStatusRequest("FAILED", COLUMN_WIDTH))).isEmpty();
        assertThat(validator.validate(new DeliveryReasonRequest(COLUMN_WIDTH))).isEmpty();
    }

    @Test
    @DisplayName("a reason wider than the column is refused")
    void refusesAnOverlongReason() {
        String tooLong = "a".repeat(256);

        assertThat(validator.validate(new UpdateDeliveryStatusRequest("FAILED", tooLong)))
                .anyMatch(violation -> "reason".equals(violation.getPropertyPath().toString()));
        assertThat(validator.validate(new DeliveryReasonRequest(tooLong)))
                .anyMatch(violation -> "reason".equals(violation.getPropertyPath().toString()));
    }

    @Test
    @DisplayName("the status endpoint still requires a target status")
    void statusEndpointRequiresAStatus() {
        assertThat(validator.validate(new UpdateDeliveryStatusRequest(" ", null)))
                .anyMatch(violation -> "status".equals(violation.getPropertyPath().toString()));
        assertThat(validator.validate(new UpdateDeliveryStatusRequest(null, null)))
                .anyMatch(violation -> "status".equals(violation.getPropertyPath().toString()));
    }
}