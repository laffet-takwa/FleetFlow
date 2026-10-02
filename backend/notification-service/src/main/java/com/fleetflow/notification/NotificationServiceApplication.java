package com.fleetflow.notification;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

import com.fleetflow.notification.config.NotificationProperties;

/**
 * Entry point of the notification service: it consumes the domain events other
 * services publish, turns them into a notification row and pushes the result to the
 * recipient's open browser stream. It owns no business logic of its own and calls
 * no other service - every payload already carries what the copy needs.
 */
@SpringBootApplication
@EnableConfigurationProperties(NotificationProperties.class)
public class NotificationServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(NotificationServiceApplication.class, args);
    }
}
