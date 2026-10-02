package com.fleetflow.warehouse.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * The topic names this service publishes to, bound from {@code fleetflow.kafka.topics}.
 *
 * <p>Bound rather than read as a constant so that CONTRACTS §7 holds everywhere: no
 * topic name is written into Java, a deployment can rename a topic through the
 * environment, and a missing key fails at startup rather than at the first publish.
 */
@Component
@ConfigurationProperties(prefix = "fleetflow.kafka.topics")
public class KafkaTopicProperties {

    /** Consumed: an order was accepted and needs its stock held. */
    private String orderCreated;

    /** Published: stock is secured for every line of an order. */
    private String inventoryReserved;

    /** Published: at least one line could not be covered. */
    private String inventoryInsufficient;

    public String getOrderCreated() {
        return orderCreated;
    }

    public void setOrderCreated(String orderCreated) {
        this.orderCreated = orderCreated;
    }

    public String getInventoryReserved() {
        return inventoryReserved;
    }

    public void setInventoryReserved(String inventoryReserved) {
        this.inventoryReserved = inventoryReserved;
    }

    public String getInventoryInsufficient() {
        return inventoryInsufficient;
    }

    public void setInventoryInsufficient(String inventoryInsufficient) {
        this.inventoryInsufficient = inventoryInsufficient;
    }
}