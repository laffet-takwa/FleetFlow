package com.fleetflow.customer.seed;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.fleetflow.common.correlation.CorrelationId;

/**
 * Inserts the demo customers whose auth-service user ids are 8..17, so the seeded
 * demo stack has a profile for every seeded login.
 */
@Component
@ConditionalOnProperty(name = "fleetflow.seed.enabled", havingValue = "true")
public class DemoDataSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoDataSeeder.class);

    private static final String COUNT_SQL = "SELECT COUNT(*) FROM customers";
    private static final String INSERT_SQL = """
            INSERT INTO customers (id, user_id, first_name, last_name, email, phone, address, city,
                                   postal_code, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;
    private static final String RESET_SEQUENCE_SQL =
            "SELECT setval('customers_id_seq', (SELECT MAX(id) FROM customers))";

    /** Each demo customer reuses its auth user id as the customer id, keeping the demo data readable. */
    private static final List<SeedCustomer> DEMO_CUSTOMERS = List.of(
            new SeedCustomer(8L, "Yasmine", "Ben Salah", "customer1@fleetflow.local", "+21620100101",
                    "12 Rue Habib Bourguiba", "Tunis", "1000"),
            new SeedCustomer(9L, "Ahmed", "Trabelsi", "customer2@fleetflow.local", "+21620100102",
                    "45 Avenue Taieb Mhiri", "Sousse", "4000"),
            new SeedCustomer(10L, "Salma", "Gharbi", "customer3@fleetflow.local", "+21620100103",
                    "8 Rue de la Republique", "Sfax", "3000"),
            new SeedCustomer(11L, "Mohamed", "Aloui", "customer4@fleetflow.local", "+21620100104",
                    "30 Avenue de la Liberte", "Ariana", "1014"),
            new SeedCustomer(12L, "Ines", "Bouazizi", "customer5@fleetflow.local", "+21620100105",
                    "7 Rue du 14 Janvier", "Ben Arous", "2013"),
            new SeedCustomer(13L, "Karim", "Chaabane", "customer6@fleetflow.local", "+21620100106",
                    "21 Boulevard Yasser Arafat", "Hammamet", "8050"),
            new SeedCustomer(14L, "Nadia", "Ferchichi", "customer7@fleetflow.local", "+21620100107",
                    "3 Rue Mohamed Slim", "Sousse", "4051"),
            new SeedCustomer(15L, "Mehdi", "Zouari", "customer8@fleetflow.local", "+21620100108",
                    "15 Avenue de la Republique", "Nabeul", "8000"),
            new SeedCustomer(16L, "Sonia", "Hamdi", "customer9@fleetflow.local", "+21620100109",
                    "9 Rue d'Espagne", "Bizerte", "7000"),
            new SeedCustomer(17L, "Walid", "Ksouri", "customer10@fleetflow.local", "+21620100110",
                    "26 Avenue Ibn El Jazzar", "Kairouan", "4100"));

    private final JdbcTemplate jdbcTemplate;

    public DemoDataSeeder(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        Long existing = jdbcTemplate.queryForObject(COUNT_SQL, Long.class);
        if (existing != null && existing > 0) {
            log.info("Customer seed skipped: {} rows already present [correlationId={}]",
                    existing, CorrelationId.getOrCreate());
            return;
        }

        OffsetDateTime now = OffsetDateTime.ofInstant(Instant.now(), ZoneOffset.UTC);
        List<Object[]> batch = DEMO_CUSTOMERS.stream()
                .map(customer -> new Object[] {
                        customer.userId(), customer.userId(), customer.firstName(), customer.lastName(),
                        customer.email(), customer.phone(), customer.address(), customer.city(),
                        customer.postalCode(), now, now })
                .toList();

        jdbcTemplate.batchUpdate(INSERT_SQL, batch);
        // Explicit ids do not advance the sequence, so the next generated one would
        // collide with the rows just inserted.
        jdbcTemplate.execute(RESET_SEQUENCE_SQL);
        log.info("Seeded {} demo customers [correlationId={}]", batch.size(), CorrelationId.getOrCreate());
    }

    private record SeedCustomer(Long userId, String firstName, String lastName, String email, String phone,
            String address, String city, String postalCode) {
    }
}
