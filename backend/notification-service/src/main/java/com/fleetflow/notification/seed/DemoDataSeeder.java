package com.fleetflow.notification.seed;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.fleetflow.common.correlation.CorrelationId;

import com.fleetflow.notification.config.NotificationProperties;
import com.fleetflow.notification.entity.Notification;
import com.fleetflow.notification.entity.NotificationLevel;
import com.fleetflow.notification.entity.NotificationType;
import com.fleetflow.notification.repository.NotificationRepository;

/**
 * Fills an empty notification centre so the demo stack opens on a populated screen
 * instead of a blank one. Runs once: a service that already has notifications is left
 * exactly as it is, whatever the demo window is.
 *
 * <p>The rows are generated from a fixed list rather than at random, so two fresh
 * environments show the same history and a screenshot of one is reproducible in the
 * other.
 */
@Component
@ConditionalOnProperty(name = "fleetflow.seed.enabled", havingValue = "true")
public class DemoDataSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoDataSeeder.class);

    private static final long FIRST_CUSTOMER_USER_ID = 8L;
    private static final int CUSTOMER_COUNT = 10;
    private static final int CUSTOMER_ROWS = 25;
    private static final int MAX_ORDER_ID = 20;
    /** Keeps the last row just inside the three day window instead of exactly on it. */
    private static final int MINUTES_BETWEEN_ROWS = 170;
    private static final int STAFF_ROWS = 4;

    private static final List<String> WAREHOUSES = List.of("Tunis Sud warehouse", "Sousse Nord warehouse",
            "Sfax Est warehouse");
    private static final List<String> DRIVERS = List.of("Karim Ben Ali", "Sami Trabelsi", "Rania Haddad");
    private static final List<String> VEHICLES = List.of("123 Tunis 4521", "78 Sousse 9087", "45 Sfax 3310");
    private static final List<String> FAILURE_REASONS = List.of("The recipient was not available.",
            "The address could not be reached.", "The parcel was refused at the door.");
    private static final List<String> SHORT_PRODUCTS = List.of("Laptop Pro 14", "Wireless Mouse", "USB-C Hub");

    /** One customer's journey, reused across users so the history looks coherent. */
    private static final List<Draft> CUSTOMER_JOURNEY = List.of(
            new Draft(NotificationType.ORDER_CONFIRMED, NotificationLevel.INFO, false),
            new Draft(NotificationType.INVENTORY_RESERVED, NotificationLevel.SUCCESS, false),
            new Draft(NotificationType.DRIVER_ASSIGNED, NotificationLevel.INFO, true),
            new Draft(NotificationType.DELIVERY_STARTED, NotificationLevel.INFO, true),
            new Draft(NotificationType.DELIVERY_COMPLETED, NotificationLevel.SUCCESS, true),
            new Draft(NotificationType.DELIVERY_FAILED, NotificationLevel.WARNING, true),
            new Draft(NotificationType.DELIVERY_CANCELLED, NotificationLevel.WARNING, true),
            new Draft(NotificationType.INVENTORY_INSUFFICIENT, NotificationLevel.ERROR, false),
            new Draft(NotificationType.ORDER_CANCELLED, NotificationLevel.WARNING, false));

    private final NotificationRepository repository;
    private final NotificationProperties properties;

    public DemoDataSeeder(NotificationRepository repository, NotificationProperties properties) {
        this.repository = repository;
        this.properties = properties;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (repository.count() > 0) {
            log.info("Notification seed skipped: the centre is not empty [correlationId={}]",
                    CorrelationId.getOrCreate());
            return;
        }

        Instant now = Instant.now();
        Instant windowStart = now.minus(3, ChronoUnit.DAYS);
        List<Notification> rows = new ArrayList<>(CUSTOMER_ROWS + STAFF_ROWS);

        for (int index = 0; index < CUSTOMER_ROWS; index++) {
            long userId = FIRST_CUSTOMER_USER_ID + (index % CUSTOMER_COUNT);
            Draft draft = CUSTOMER_JOURNEY.get(index % CUSTOMER_JOURNEY.size());
            long orderId = 1 + (index % MAX_ORDER_ID);
            Long deliveryId = draft.deliveryScoped() ? Long.valueOf(1 + (index * 7 % MAX_ORDER_ID)) : null;
            rows.add(notification(
                    userId, draft, orderId, deliveryId,
                    // One row in three is left unread, which is what a live centre looks like.
                    index % 3 != 0,
                    windowStart.plus(index * MINUTES_BETWEEN_ROWS, ChronoUnit.MINUTES),
                    index));
        }

        long operationsUserId = properties.operationsUserId();
        for (int index = 0; index < STAFF_ROWS; index++) {
            long orderId = 3 + (index * 4 % MAX_ORDER_ID);
            long deliveryId = 1 + (index * 5 % MAX_ORDER_ID);
            Notification staffNotification = notification(operationsUserId,
                    new Draft(NotificationType.DRIVER_ASSIGNED, NotificationLevel.INFO, true),
                    orderId, deliveryId, index % 2 == 0,
                    windowStart.plus((CUSTOMER_ROWS + index) * MINUTES_BETWEEN_ROWS, ChronoUnit.MINUTES),
                    index);
            staffNotification.setTitle("New delivery assigned");
            staffNotification.setMessage("Delivery #%d for order #%d is awaiting dispatch."
                    .formatted(deliveryId, orderId));
            rows.add(staffNotification);
        }

        repository.saveAll(rows);
        log.info("Seeded {} demo notifications for customers {}..{} and the operations desk [correlationId={}]",
                rows.size(), FIRST_CUSTOMER_USER_ID, FIRST_CUSTOMER_USER_ID + CUSTOMER_COUNT - 1,
                CorrelationId.getOrCreate());
    }

    private Notification notification(long userId, Draft draft, long orderId, Long deliveryId, boolean read,
            Instant createdAt, int index) {

        Notification notification = new Notification();
        notification.setUserId(userId);
        notification.setType(draft.type());
        notification.setLevel(draft.level());
        notification.setTitle(title(draft.type(), orderId));
        notification.setMessage(message(draft.type(), orderId, index));
        notification.setOrderId(orderId);
        notification.setDeliveryId(deliveryId);
        notification.setRead(read);
        // Set explicitly: the entity callback would otherwise stamp every row with the
        // boot time, leaving the demo centre with no history to look at.
        notification.setCreatedAt(createdAt);
        return notification;
    }

    private static String title(NotificationType type, long orderId) {
        return switch (type) {
            case ORDER_CONFIRMED -> "Order #%d confirmed".formatted(orderId);
            case INVENTORY_RESERVED -> "Items reserved";
            case INVENTORY_INSUFFICIENT, ORDER_CANCELLED -> "Order #%d cancelled".formatted(orderId);
            case DRIVER_ASSIGNED -> "Driver assigned";
            case DELIVERY_STARTED -> "On the way";
            case DELIVERY_COMPLETED -> "Order #%d delivered".formatted(orderId);
            case DELIVERY_FAILED -> "Delivery problem";
            case DELIVERY_CANCELLED -> "Delivery cancelled";
        };
    }

    /** {@code index} only picks which demo warehouse, driver or reason is used. */
    private static String message(NotificationType type, long orderId, int index) {
        return switch (type) {
            case ORDER_CONFIRMED -> "Your order #%d has been received and is being prepared.".formatted(orderId);
            case INVENTORY_RESERVED -> "Your order #%d has been reserved at %s and is ready for dispatch."
                    .formatted(orderId, WAREHOUSES.get(index % WAREHOUSES.size()));
            case INVENTORY_INSUFFICIENT -> "We could not fulfil order #%d because %s is out of stock."
                    .formatted(orderId, SHORT_PRODUCTS.get(index % SHORT_PRODUCTS.size()));
            case DRIVER_ASSIGNED -> "Your order #%d has been assigned to %s (%s)."
                    .formatted(orderId, DRIVERS.get(index % DRIVERS.size()), VEHICLES.get(index % VEHICLES.size()));
            case DELIVERY_STARTED -> "Your delivery for order #%d is on the way.".formatted(orderId);
            case DELIVERY_COMPLETED -> "Your order #%d has been delivered. Thank you for choosing FleetFlow!"
                    .formatted(orderId);
            case DELIVERY_FAILED -> "We could not complete the delivery of order #%d. %s"
                    .formatted(orderId, FAILURE_REASONS.get(index % FAILURE_REASONS.size()));
            case DELIVERY_CANCELLED -> "The delivery for order #%d has been cancelled.".formatted(orderId);
            case ORDER_CANCELLED -> "Your order #%d was cancelled before it was dispatched.".formatted(orderId);
        };
    }

    private record Draft(NotificationType type, NotificationLevel level, boolean deliveryScoped) {
    }
}
