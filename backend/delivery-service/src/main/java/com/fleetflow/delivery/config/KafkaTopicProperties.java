package com.fleetflow.delivery.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Topic names come from configuration, never from constants, so a deployment can
 * point the service at a different bus without a rebuild.
 */
@ConfigurationProperties(prefix = "fleetflow.kafka.topics")
public class KafkaTopicProperties {

    private String inventoryReserved;
    private String deliveryAssigned;
    private String deliveryPickedUp;
    private String deliveryStarted;
    private String deliveryCompleted;
    private String deliveryFailed;
    private String deliveryCancelled;

    public String getInventoryReserved() {
        return inventoryReserved;
    }

    public void setInventoryReserved(String inventoryReserved) {
        this.inventoryReserved = inventoryReserved;
    }

    public String getDeliveryAssigned() {
        return deliveryAssigned;
    }

    public void setDeliveryAssigned(String deliveryAssigned) {
        this.deliveryAssigned = deliveryAssigned;
    }

    public String getDeliveryPickedUp() {
        return deliveryPickedUp;
    }

    public void setDeliveryPickedUp(String deliveryPickedUp) {
        this.deliveryPickedUp = deliveryPickedUp;
    }

    public String getDeliveryStarted() {
        return deliveryStarted;
    }

    public void setDeliveryStarted(String deliveryStarted) {
        this.deliveryStarted = deliveryStarted;
    }

    public String getDeliveryCompleted() {
        return deliveryCompleted;
    }

    public void setDeliveryCompleted(String deliveryCompleted) {
        this.deliveryCompleted = deliveryCompleted;
    }

    public String getDeliveryFailed() {
        return deliveryFailed;
    }

    public void setDeliveryFailed(String deliveryFailed) {
        this.deliveryFailed = deliveryFailed;
    }

    public String getDeliveryCancelled() {
        return deliveryCancelled;
    }

    public void setDeliveryCancelled(String deliveryCancelled) {
        this.deliveryCancelled = deliveryCancelled;
    }
}
