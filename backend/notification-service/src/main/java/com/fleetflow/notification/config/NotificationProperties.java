package com.fleetflow.notification.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Tunables of the notification service itself. */
@ConfigurationProperties(prefix = "fleetflow.notifications")
public class NotificationProperties {

    /**
     * Seeded operations account that receives the staff copy of every delivery
     * assignment. Defaults to the auth-service operations user id.
     */
    private long operationsUserId = 2L;

    public long operationsUserId() {
        return operationsUserId;
    }

    public void setOperationsUserId(long operationsUserId) {
        this.operationsUserId = operationsUserId;
    }
}
