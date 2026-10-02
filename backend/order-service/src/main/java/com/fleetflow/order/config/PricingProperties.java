package com.fleetflow.order.config;

import java.math.BigDecimal;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Commercial parameters. Kept out of the code so operations can change the delivery
 * fee for a demo without a rebuild, and so every order is priced from one place.
 */
@Component
@ConfigurationProperties(prefix = "fleetflow.pricing")
public class PricingProperties {

    /** Flat fee added to every order, independent of its size. */
    private BigDecimal deliveryFee = new BigDecimal("8.00");

    /** ISO 4217 code; the TND dinar is the platform default. */
    private String currency = "TND";

    public BigDecimal getDeliveryFee() {
        return deliveryFee;
    }

    public void setDeliveryFee(BigDecimal deliveryFee) {
        this.deliveryFee = deliveryFee;
    }

    public String getCurrency() {
        return currency;
    }

    public void setCurrency(String currency) {
        this.currency = currency;
    }
}
