package com.fleetflow.auth.seed;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import com.fleetflow.common.correlation.CorrelationId;
import com.fleetflow.common.security.FleetRole;

/**
 * Inserts the demo identities every other service references by id.
 *
 * <p>Ids are explicit and the sequence is rewound afterwards, so an account created
 * through the API keeps getting ids that no other fixture can collide with.
 */
@Component
@ConditionalOnProperty(name = "fleetflow.seed.enabled", havingValue = "true")
public class DemoDataSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoDataSeeder.class);

    /** Shared password for every demo account; documented in docs/CONTRACTS.md S5. */
    static final String DEMO_PASSWORD = "Password123!";

    private static final String INSERT_SQL = """
            INSERT INTO users (id, first_name, last_name, email, phone, password_hash, address, role, enabled,
                               created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, now(), now())
            """;

    private static final String RESET_SEQUENCE_SQL = "SELECT setval('users_id_seq', (SELECT MAX(id) FROM users))";

    private static final List<DemoUser> DEMO_USERS = List.of(
            new DemoUser(1L, "admin@fleetflow.local", "Amine", "Bouzid", FleetRole.ADMIN, "+21620100101",
                    "1 rue de la Republique, Tunis"),
            new DemoUser(2L, "operations@fleetflow.local", "Sonia", "Trabelsi", FleetRole.OPERATIONS, "+21620100102",
                    "5 avenue Habib Bourguiba, Tunis"),
            new DemoUser(3L, "driver1@fleetflow.local", "Karim", "Ben Salah", FleetRole.DRIVER, "+21620100103",
                    "12 rue des Palmiers, Sfax"),
            new DemoUser(4L, "driver2@fleetflow.local", "Yassine", "Haddad", FleetRole.DRIVER, "+21620100104",
                    "8 rue de France, Sousse"),
            new DemoUser(5L, "driver3@fleetflow.local", "Nabil", "Jouini", FleetRole.DRIVER, "+21620100105",
                    "30 avenue de la Liberte, Nabeul"),
            new DemoUser(6L, "driver4@fleetflow.local", "Rami", "Chaabane", FleetRole.DRIVER, "+21620100106",
                    "7 rue Mohamed V, Bizerte"),
            new DemoUser(7L, "driver5@fleetflow.local", "Sami", "Khelifi", FleetRole.DRIVER, "+21620100107",
                    "14 rue 18 Janvier, Kairouan"),
            new DemoUser(8L, "customer1@fleetflow.local", "Ahmed", "Ben Ali", FleetRole.CUSTOMER, "+21620100208",
                    "22 rue Ibn Khaldoun, Tunis"),
            new DemoUser(9L, "customer2@fleetflow.local", "Leila", "Gharbi", FleetRole.CUSTOMER, "+21620100209",
                    "3 rue des Roses, Ariana"),
            new DemoUser(10L, "customer3@fleetflow.local", "Mehdi", "Bouazizi", FleetRole.CUSTOMER, "+21620100210",
                    "45 avenue Charles de Gaulle, Sfax"),
            new DemoUser(11L, "customer4@fleetflow.local", "Fatma", "Zouari", FleetRole.CUSTOMER, "+21620100211",
                    "9 rue 9 Avril, Sousse"),
            new DemoUser(12L, "customer5@fleetflow.local", "Hichem", "Meddeb", FleetRole.CUSTOMER, "+21620100212",
                    "17 rue de Tunis, Nabeul"),
            new DemoUser(13L, "customer6@fleetflow.local", "Ines", "Larbi", FleetRole.CUSTOMER, "+21620100213",
                    "2 rue du Port, Bizerte"),
            new DemoUser(14L, "customer7@fleetflow.local", "Wajdi", "Mansouri", FleetRole.CUSTOMER, "+21620100214",
                    "61 rue Al Massadi, Kairouan"),
            new DemoUser(15L, "customer8@fleetflow.local", "Sarra", "Ben Youssef", FleetRole.CUSTOMER, "+21620100215",
                    "11 rue des Oliviers, Monastir"),
            new DemoUser(16L, "customer9@fleetflow.local", "Oussama", "Dridi", FleetRole.CUSTOMER, "+21620100216",
                    "28 avenue du Japon, Tunis"),
            new DemoUser(17L, "customer10@fleetflow.local", "Amel", "Cherif", FleetRole.CUSTOMER, "+21620100217",
                    "4 rue des Olivettes, Hammamet"));

    private final JdbcTemplate jdbcTemplate;
    private final PasswordEncoder passwordEncoder;

    public DemoDataSeeder(JdbcTemplate jdbcTemplate, PasswordEncoder passwordEncoder) {
        this.jdbcTemplate = jdbcTemplate;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public void run(ApplicationArguments args) {
        Long existing = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM users", Long.class);
        if (existing != null && existing > 0) {
            log.info("Demo identities skipped, {} user(s) already present", existing);
            return;
        }

        // One hash for every demo account: they all share a published password.
        String passwordHash = passwordEncoder.encode(DEMO_PASSWORD);
        jdbcTemplate.batchUpdate(INSERT_SQL, DEMO_USERS.stream()
                .map(user -> new Object[] {
                        user.id(), user.firstName(), user.lastName(), user.email(), user.phone(),
                        passwordHash, user.address(), user.role().name(), Boolean.TRUE })
                .toList());

        // Auto-generated ids must continue after the fixture range, never inside it.
        jdbcTemplate.execute(RESET_SEQUENCE_SQL);

        log.info("Seeded {} demo identities, ids 1..{} [correlationId={}]", DEMO_USERS.size(),
                DEMO_USERS.get(DEMO_USERS.size() - 1).id(), CorrelationId.getOrCreate());
    }

    private record DemoUser(Long id, String email, String firstName, String lastName, FleetRole role, String phone,
            String address) {
    }
}