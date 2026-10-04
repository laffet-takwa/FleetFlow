package com.fleetflow.order.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * The topics this service publishes to, bound from {@code fleetflow.kafka.topics}.
 *
 * <p>Bound rather than read from {@link com.fleetflow.common.event.KafkaTopics} for the
 * same reason every other service does it: a deployment may rename a topic through the
 * environment, and a name written into Java would keep publishing to the old topic while
 * every consumer had already moved. Reading the constant here would have made the
 * published topic and the consumed topics disagree as soon as the property was overridden.
 */
@Component
@ConfigurationProperties(prefix = "fleetflow.kafka.topics")
public class OrderTopicProperties {

    /** Published once an order has been priced and stored. */
    private String orderCreated;

    public String getOrderCreated() {
        return orderCreated;
    }

    public void setOrderCreated(String orderCreated) {
        this.orderCreated = orderCreated;
    }
}