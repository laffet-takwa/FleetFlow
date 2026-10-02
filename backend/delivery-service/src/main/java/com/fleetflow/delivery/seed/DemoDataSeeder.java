package com.fleetflow.delivery.seed;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.fleetflow.delivery.entity.Delivery;
import com.fleetflow.delivery.entity.DeliveryStatus;
import com.fleetflow.delivery.entity.Driver;
import com.fleetflow.delivery.entity.DriverStatus;
import com.fleetflow.delivery.entity.Vehicle;
import com.fleetflow.delivery.entity.VehicleStatus;
import com.fleetflow.delivery.entity.VehicleType;
import com.fleetflow.delivery.repository.DeliveryRepository;
import com.fleetflow.delivery.repository.DriverRepository;
import com.fleetflow.delivery.repository.VehicleRepository;

/**
 * Demo fleet for a fresh local stack. Idempotent: the whole block is skipped once
 * drivers exist, so a restart never duplicates rows.
 *
 * <p>{@code user_id} 3..7 match the driver1..driver5 accounts seeded by auth-service
 * (see docs/CONTRACTS.md section 5) and order ids 1..10 / customer ids 8..17 match
 * the seeded orders and customers.
 */
@Component
@ConditionalOnProperty(name = "fleetflow.seed.enabled", havingValue = "true")
public class DemoDataSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoDataSeeder.class);

    private final DriverRepository driverRepository;
    private final VehicleRepository vehicleRepository;
    private final DeliveryRepository deliveryRepository;
    private final JdbcTemplate jdbcTemplate;

    public DemoDataSeeder(DriverRepository driverRepository,
            VehicleRepository vehicleRepository,
            DeliveryRepository deliveryRepository,
            JdbcTemplate jdbcTemplate) {
        this.driverRepository = driverRepository;
        this.vehicleRepository = vehicleRepository;
        this.deliveryRepository = deliveryRepository;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (driverRepository.count() > 0) {
            log.info("Demo data already present, skipping the seeder");
            return;
        }

        List<Driver> drivers = List.of(
                driver(3L, "Karim Ben Salah", "TN-DL-102938", "+21620123456", DriverStatus.AVAILABLE),
                driver(4L, "Yassine Trabelsi", "TN-DL-204517", "+21620456789", DriverStatus.AVAILABLE),
                driver(5L, "Sami Bouazizi", "TN-DL-311890", "+21620987654", DriverStatus.AVAILABLE),
                driver(6L, "Nabil Hammami", "TN-DL-418263", "+21622567890", DriverStatus.OFFLINE),
                driver(7L, "Amine Guesmi", "TN-DL-523701", "+21623789012", DriverStatus.ON_DELIVERY));
        driverRepository.saveAll(drivers);

        List<Vehicle> vehicles = List.of(
                vehicle("123 تونس 4567", VehicleType.VAN, 800, VehicleStatus.AVAILABLE),
                vehicle("214 تونس 7831", VehicleType.MOTORCYCLE, 40, VehicleStatus.AVAILABLE),
                vehicle("305 تونس 9042", VehicleType.CAR, 350, VehicleStatus.AVAILABLE),
                vehicle("478 تونس 2256", VehicleType.TRUCK, 3000, VehicleStatus.AVAILABLE),
                vehicle("561 تونس 3378", VehicleType.VAN, 700, VehicleStatus.IN_USE));
        vehicleRepository.saveAll(vehicles);

        deliveryRepository.saveAll(List.of(
                delivered(1L, 8L, 1L, 1, 6, 2),
                delivered(2L, 9L, 1L, 2, 5, 5),
                delivered(3L, 10L, 2L, 1, 4, 9),
                delivered(4L, 11L, 2L, 3, 3, 12),
                delivered(5L, 12L, 3L, 4, 2, 15),
                inTransit(6L, 13L, 5L, 5, 16, 1),
                inTransit(7L, 14L, 4L, 4, 17, 2),
                assigned(8L, 15L, 1L, 2, 3),
                pickedUp(9L, 16L, 2L, 3, 4),
                created(10L, 17L, 5)));

        resetSequences();
        log.info("Seeded 5 drivers, 5 vehicles and 10 deliveries");
    }

    /**
     * The rows were inserted with generated keys, so the identity sequences are already
     * ahead; this only makes that explicit and keeps the ids predictable in psql.
     */
    private void resetSequences() {
        jdbcTemplate.execute("SELECT setval('drivers_id_seq', (SELECT MAX(id) FROM drivers))");
        jdbcTemplate.execute("SELECT setval('vehicles_id_seq', (SELECT MAX(id) FROM vehicles))");
        jdbcTemplate.execute("SELECT setval('deliveries_id_seq', (SELECT MAX(id) FROM deliveries))");
    }

    private static Driver driver(Long userId, String fullName, String licence, String phone, DriverStatus status) {
        Driver driver = new Driver();
        driver.setUserId(userId);
        driver.setFullName(fullName);
        driver.setLicenseNumber(licence);
        driver.setPhone(phone);
        driver.setStatus(status);
        return driver;
    }

    private static Vehicle vehicle(String registration, VehicleType type, int capacity, VehicleStatus status) {
        Vehicle vehicle = new Vehicle();
        vehicle.setRegistrationNumber(registration);
        vehicle.setType(type);
        vehicle.setCapacity(capacity);
        vehicle.setStatus(status);
        return vehicle;
    }

    // ------------------------------------------------------------------ deliveries

    private static Delivery delivered(Long orderId, Long customerId, Long driverIndex, int vehicleIndex,
            int daysAgo, int hoursAgo) {
        Delivery delivery = base(orderId, customerId, driverIndex, vehicleIndex, daysAgo);
        delivery.setStatus(DeliveryStatus.DELIVERED);
        delivery.setStartedAt(Instant.now().minus(Duration.ofDays(daysAgo)).minus(Duration.ofMinutes(20)));
        delivery.setCompletedAt(Instant.now().minus(Duration.ofHours(hoursAgo)));
        delivery.setProofOfDelivery("Signed by the customer at the door");
        return delivery;
    }

    private static Delivery inTransit(Long orderId, Long customerId, Long driverIndex, int vehicleIndex,
            int daysAgo, int hoursAgo) {
        Delivery delivery = base(orderId, customerId, driverIndex, vehicleIndex, daysAgo);
        delivery.setStatus(DeliveryStatus.IN_TRANSIT);
        delivery.setStartedAt(Instant.now().minus(Duration.ofHours(hoursAgo)));
        return delivery;
    }

    private static Delivery assigned(Long orderId, Long customerId, Long driverIndex, int vehicleIndex, int daysAgo) {
        Delivery delivery = base(orderId, customerId, driverIndex, vehicleIndex, daysAgo);
        delivery.setStatus(DeliveryStatus.ASSIGNED);
        return delivery;
    }

    private static Delivery pickedUp(Long orderId, Long customerId, Long driverIndex, int vehicleIndex, int daysAgo) {
        Delivery delivery = base(orderId, customerId, driverIndex, vehicleIndex, daysAgo);
        delivery.setStatus(DeliveryStatus.PICKED_UP);
        return delivery;
    }

    private static Delivery created(Long orderId, Long customerId, int daysAgo) {
        Delivery delivery = base(orderId, customerId, null, 0, daysAgo);
        delivery.setStatus(DeliveryStatus.CREATED);
        delivery.setDriverId(null);
        delivery.setVehicleId(null);
        return delivery;
    }

    private static Delivery base(Long orderId, Long customerId, Long driverIndex, int vehicleIndex, int daysAgo) {
        Instant createdAt = Instant.now().minus(Duration.ofDays(daysAgo));
        String[] streets = { "Rue Habib Bourguiba", "Avenue Habib Bourguiba", "Rue de la Liberté",
                "Boulevard du 7 Novembre", "Avenue de la Liberté", "Rue Ibn Khaldoun",
                "Cité Les Kadhras", "Rue Tahar Haddad", "Avenue Mourad Slimane", "Rue Al Qods" };
        String[] cities = { "Tunis", "Ariana", "Sousse", "Sfax", "Bizerte" };
        String[] postals = { "1000", "1010", "4000", "3000", "7000" };

        Delivery delivery = new Delivery();
        delivery.setOrderId(orderId);
        delivery.setCustomerId(customerId);
        delivery.setStatus(DeliveryStatus.CREATED);
        delivery.setPickupAddress("Zone Industrielle El Mghira, Tunis 1000");
        delivery.setDeliveryAddress(streets[(int) (orderId % streets.length)] + " " + (10 + orderId));
        delivery.setCity(cities[(int) (orderId % cities.length)]);
        delivery.setPostalCode(postals[(int) (orderId % postals.length)]);
        delivery.setCustomerName("Customer " + customerId);
        delivery.setCustomerPhone("+216" + (70 + customerId) + "000" + (100 + customerId));
        delivery.setScheduledAt(createdAt.plus(Duration.ofHours(4)));
        delivery.setCreatedAt(createdAt);
        delivery.setUpdatedAt(createdAt);
        if (driverIndex != null) {
            delivery.setDriverId(driverIndex);
            delivery.setVehicleId((long) vehicleIndex);
        }
        return delivery;
    }
}
