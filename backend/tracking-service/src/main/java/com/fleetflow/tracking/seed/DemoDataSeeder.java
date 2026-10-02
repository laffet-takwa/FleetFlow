package com.fleetflow.tracking.seed;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;

import com.fleetflow.tracking.document.DeliveryStatus;
import com.fleetflow.tracking.document.DeliveryTrackingState;
import com.fleetflow.tracking.document.LocationHistory;
import com.fleetflow.tracking.dto.LocationResponse;
import com.fleetflow.tracking.redis.LatestLocationStore;

/**
 * Demo data for the local stack: the deliveries the delivery-service seed creates, with a
 * plausible trail behind each one that is still on the move so the map has something to
 * draw before any driver has driven anywhere.
 *
 * <p>Gated by {@code fleetflow.seed.enabled} and idempotent by construction: it refuses to
 * run once the collection is non empty, so a restart never duplicates history.
 */
@Component
@ConditionalOnProperty(name = "fleetflow.seed.enabled", havingValue="true")
public class DemoDataSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoDataSeeder.class);

    /** Central Tunis, used as the pickup point every seeded route starts from. */
    private static final double PICKUP_LAT = 36.8065;
    private static final double PICKUP_LON = 10.1815;

    private static final int MIN_POINTS = 25;
    private static final int MAX_POINTS = 60;
    private static final int MIN_GAP_SECONDS = 60;
    private static final int MAX_GAP_SECONDS = 120;

    /** Fixed seed: the demo trail must look identical on every fresh boot. */
    private static final long RANDOM_SEED = 20261002L;

    /**
     * Mirrors the address and city of the delivery-service demo seed, so the address shown
     * on the live map is the address shown on the delivery board for the same delivery.
     */
    private static final String[] STREETS = { "Rue Habib Bourguiba", "Avenue Habib Bourguiba",
            "Rue de la Liberté", "Boulevard du 7 Novembre", "Avenue de la Liberté", "Rue Ibn Khaldoun",
            "Cité Les Kadhras", "Rue Tahar Haddad", "Avenue Mourad Slimane", "Rue Al Qods" };

    private static final String[] CITIES = { "Tunis", "Ariana", "Sousse", "Sfax", "Bizerte" };

    private static final Map<String, double[]> CITY_COORDINATES = Map.of(
            "Tunis", new double[] { 36.8065, 10.1815 },
            "Ariana", new double[] { 36.8606, 10.1953 },
            "Sousse", new double[] { 35.8256, 10.6360 },
            "Sfax", new double[] { 34.7406, 10.7603 },
            "Bizerte", new double[] { 37.2744, 9.8739 });

    /**
     * The ten deliveries the delivery-service seed creates, in the same order.
     *
     * <p>Both driver identifiers are stored, because they live in different id spaces and
     * neither can be derived from the other here: the delivery-service inserts its
     * {@code drivers} rows with generated keys 1..5 for the auth users 3..7, and
     * {@code DeliveryAssignedPayload} carries the key in {@code driverId} and the auth user
     * id in {@code driverUserId}. {@code driverKey} therefore reproduces what
     * {@code deliveries.driver_id} holds, and {@code driverUserId} what a driver of that
     * delivery presents as their JWT subject, which is the value the authorisation checks
     * compare. Delivery 10 is created without a driver in the delivery-service seed, so it
     * has neither.
     */
    private static final List<SeededDelivery> DELIVERIES = List.of(
            new SeededDelivery(1L, 1L, 8L, 1L, 3L, "Karim Ben Salah", DeliveryStatus.DELIVERED),
            new SeededDelivery(2L, 2L, 9L, 1L, 3L, "Karim Ben Salah", DeliveryStatus.DELIVERED),
            new SeededDelivery(3L, 3L, 10L, 2L, 4L, "Yassine Trabelsi", DeliveryStatus.DELIVERED),
            new SeededDelivery(4L, 4L, 11L, 3L, 5L, "Sami Bouazizi", DeliveryStatus.DELIVERED),
            new SeededDelivery(5L, 5L, 12L, 4L, 6L, "Nabil Hammami", DeliveryStatus.DELIVERED),
            new SeededDelivery(6L, 6L, 13L, 5L, 7L, "Amine Guesmi", DeliveryStatus.IN_TRANSIT),
            new SeededDelivery(7L, 7L, 14L, 4L, 6L, "Nabil Hammami", DeliveryStatus.IN_TRANSIT),
            new SeededDelivery(8L, 8L, 15L, 1L, 3L, "Karim Ben Salah", DeliveryStatus.ASSIGNED),
            new SeededDelivery(9L, 9L, 16L, 2L, 4L, "Yassine Trabelsi", DeliveryStatus.PICKED_UP),
            new SeededDelivery(10L, 10L, 17L, null, null, null, DeliveryStatus.CREATED));

    private final MongoTemplate mongoTemplate;
    private final LatestLocationStore latestLocationStore;
    private final Clock clock;

    public DemoDataSeeder(MongoTemplate mongoTemplate, LatestLocationStore latestLocationStore, Clock clock) {
        this.mongoTemplate = mongoTemplate;
        this.latestLocationStore = latestLocationStore;
        this.clock = clock;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (mongoTemplate.count(new Query(), DeliveryTrackingState.class) > 0) {
            log.info("Tracking seed skipped: delivery_tracking_state already holds documents");
            return;
        }
        Random random = new Random(RANDOM_SEED);
        Instant now = clock.instant();
        int storedStates = 0;
        int storedFixes = 0;

        for (SeededDelivery delivery : DELIVERIES) {
            boolean active = !DeliveryStatus.isTerminal(delivery.status())
                    && !DeliveryStatus.CREATED.equals(delivery.status());
            DeliveryTrackingState state = buildState(delivery, now, active);
            if (!active) {
                mongoTemplate.save(state);
                storedStates++;
                continue;
            }

            List<LocationResponse> trail = buildTrail(state, delivery, now, random);
            for (LocationResponse fix : trail) {
                mongoTemplate.save(toHistory(state, fix));
            }
            storedFixes += trail.size();
            state.setLocationCount(trail.size());
            state.setLastLocationAt(trail.get(trail.size() - 1).recordedAt());
            mongoTemplate.save(state);
            storedStates++;

            latestLocationStore.put(trail.get(trail.size() - 1));
        }
        log.info("Seeded {} tracked deliveries and {} positions", storedStates, storedFixes);
    }

    private DeliveryTrackingState buildState(SeededDelivery delivery, Instant now, boolean active) {
        DeliveryTrackingState state = new DeliveryTrackingState();
        state.setDeliveryId(delivery.deliveryId());
        state.setOrderId(delivery.orderId());
        state.setCustomerId(delivery.customerId());
        state.setDriverId(delivery.driverKey());
        state.setDriverUserId(delivery.driverUserId());
        state.setDriverName(delivery.driverName());
        state.setStatus(delivery.status());
        state.setDestination(destinationOf(delivery.orderId()));
        state.setCity(cityOf(delivery.orderId()));
        state.setTrackingEnabled(active);
        state.setActivatedAt(active ? now.minus(Duration.ofMinutes(20 + delivery.orderId() * 7L))
                : now.minus(Duration.ofDays(2)));
        return state;
    }

    /**
     * A straight line from the pickup to the drop-off, walked forwards from the oldest
     * point, so the newest fix of an active delivery lands exactly on "now" and the demo
     * map shows every van as online. A deterministic jitter keeps the line from looking
     * like a ruler.
     */
    private List<LocationResponse> buildTrail(DeliveryTrackingState state, SeededDelivery delivery, Instant now,
            Random random) {
        double[] target = CITY_COORDINATES.get(state.getCity());
        int points = MIN_POINTS + random.nextInt(MAX_POINTS - MIN_POINTS + 1);
        List<LocationResponse> trail = new ArrayList<>(points);

        long elapsed = 0;
        long[] offsets = new long[points];
        for (int i = 0; i < points; i++) {
            if (i > 0) {
                elapsed += MIN_GAP_SECONDS + random.nextInt(MAX_GAP_SECONDS - MIN_GAP_SECONDS + 1);
            }
            offsets[i] = elapsed;
        }

        for (int i = 0; i < points; i++) {
            double progress = (double) i / (points - 1);
            double latitude = PICKUP_LAT + (target[0] - PICKUP_LAT) * progress;
            double longitude = PICKUP_LON + (target[1] - PICKUP_LON) * progress;
            double jitter = (random.nextDouble() - 0.5) * 0.004;
            Instant recordedAt = now.minusSeconds(offsets[points - 1 - i]);

            trail.add(new LocationResponse(
                    state.getDeliveryId(),
                    state.getDriverId(),
                    state.getCustomerId(),
                    round(latitude + jitter),
                    round(longitude + jitter),
                    round(28.0 + random.nextDouble() * 34.0),
                    round(random.nextDouble() * 360.0),
                    recordedAt,
                    recordedAt.plusMillis(250)));
        }
        return trail;
    }

    private String destinationOf(long orderId) {
        return STREETS[(int) (orderId % STREETS.length)] + " " + (10 + orderId);
    }

    private String cityOf(long orderId) {
        return CITIES[(int) (orderId % CITIES.length)];
    }

    private LocationHistory toHistory(DeliveryTrackingState state, LocationResponse fix) {
        LocationHistory history = new LocationHistory();
        history.setDeliveryId(fix.deliveryId());
        history.setDriverId(state.getDriverId());
        history.setCustomerId(state.getCustomerId());
        history.setLatitude(fix.latitude());
        history.setLongitude(fix.longitude());
        history.setSpeedKph(fix.speedKph());
        history.setHeading(fix.heading());
        history.setRecordedAt(fix.recordedAt());
        history.setReceivedAt(fix.receivedAt());
        return history;
    }

    private static double round(double value) {
        return Math.round(value * 1_000_000d) / 1_000_000d;
    }

    private record SeededDelivery(long deliveryId, long orderId, long customerId, Long driverKey, Long driverUserId,
            String driverName, String status) {
    }
}
