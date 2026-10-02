package com.fleetflow.warehouse.seed;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
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

import com.fleetflow.warehouse.entity.WarehouseStatus;
import com.fleetflow.warehouse.service.ProductCategories;

/**
 * Fills a fresh database with two warehouses, twenty catalogue entries and enough stock
 * for the demo to show every state the UI renders.
 *
 * <p>Ids are explicit because the platform is seeded as a set: order-service's demo
 * orders quote product ids 1..20 and the reservation rule reads the preferred warehouse
 * by id, so both are only stable if this seeder pins them. The guard is the product
 * count, which makes every boot after the first a no-op.
 */
@Component
@ConditionalOnProperty(name = "fleetflow.seed.enabled", havingValue = "true")
public class DemoDataSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoDataSeeder.class);

    private static final String COUNT_PRODUCTS_SQL = "SELECT COUNT(*) FROM products";
    private static final String INSERT_WAREHOUSE_SQL = """
            INSERT INTO warehouses (id, name, address, city, capacity, status, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """;
    private static final String INSERT_PRODUCT_SQL = """
            INSERT INTO products (id, sku, name, description, category, price, active, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;
    private static final String INSERT_INVENTORY_SQL = """
            INSERT INTO inventory (warehouse_id, product_id, available_quantity, reserved_quantity, updated_at)
            VALUES (?, ?, ?, ?, ?)
            """;
    private static final String RESET_WAREHOUSES_SEQUENCE_SQL =
            "SELECT setval('warehouses_id_seq', (SELECT MAX(id) FROM warehouses))";
    private static final String RESET_PRODUCTS_SEQUENCE_SQL =
            "SELECT setval('products_id_seq', (SELECT MAX(id) FROM products))";

    /** Matches {@code fleetflow.reservation.preferred-warehouse-id}, which fulfils every order. */
    private static final long TUNIS_WAREHOUSE_ID = 1L;
    private static final long SOUSSE_WAREHOUSE_ID = 2L;

    private static final List<SeedWarehouse> WAREHOUSES = List.of(
            new SeedWarehouse(TUNIS_WAREHOUSE_ID, "Tunis Centre Warehouse", "Zone Industrielle El Mghira",
                    "Tunis", 50000),
            new SeedWarehouse(SOUSSE_WAREHOUSE_ID, "Sousse Warehouse", "Route de Sousse KM 3.5, Cité Farhat",
                    "Sousse", 30000));

    /**
     * Tunisian market catalogue, one row per product id from 1 to 20.
     *
     * <p>The stock columns are the deliberate part. Tunis holds every product, Sousse only
     * a subset, and four Tunis rows are pinned at the boundary cases: ids 4, 12 and 19 sit
     * inside the low-stock band and id 20 is empty, so the low-stock badge and the
     * out-of-stock badge are both visible on a fresh database instead of only being
     * reachable by breaking the seed.
     */
    private static final List<SeedProduct> PRODUCTS = List.of(
            new SeedProduct(1L, "FF-GR-0001", "Huile d'olive extra vierge 1L",
                    "Premiere pression a froid, bouteille verre 1L", ProductCategories.GROCERY, "34.900",
                    240, 120),
            new SeedProduct(2L, "FF-GR-0002", "Carton 6 x Brik UHT 1L",
                    "Lait demi-ecreme longue conservation", ProductCategories.GROCERY, "8.500", 180, 300),
            new SeedProduct(3L, "FF-GR-0003", "Semoule fine 1kg",
                    "Semoule de ble dur pour couscous", ProductCategories.GROCERY, "3.950", 95, 260),
            new SeedProduct(4L, "FF-GR-0004", "Cafe moulu Arabe 250g",
                    "Moulu fin pour preparation traditionnelle", ProductCategories.GROCERY, "12.750", 6, null),
            new SeedProduct(5L, "FF-EL-0005", "Smart TV Samsung 55 pouces 4K",
                    "TV LED UHD avec assistant vocale", ProductCategories.ELECTRONICS, "1299.000", 150, 48),
            new SeedProduct(6L, "FF-EL-0006", "Smartphone Galaxy A05 128Go",
                    "6.7 pouces, 128 Go de stockage", ProductCategories.ELECTRONICS, "749.000", 120, 45),
            new SeedProduct(7L, "FF-EL-0007", "Enceinte portable Bluetooth",
                    "Etanche IP67, 15h d'autonomie", ProductCategories.ELECTRONICS, "89.900", 88, 95),
            new SeedProduct(8L, "FF-EL-0008", "Machine a cafe expresso Delonghi",
                    "Barista compacte 15 bar", ProductCategories.ELECTRONICS, "549.000", 60, null),
            new SeedProduct(9L, "FF-HO-0009", "Aspirateur robot Xiaomi S10",
                    "Navigation lidar, 4000 Pa d'aspiration", ProductCategories.HOME, "699.000", 75, 62),
            new SeedProduct(10L, "FF-HO-0010", "Set 6 casseroles inox 20cm",
                    "Inox 18/10 avec revetement anti-adherent", ProductCategories.HOME, "129.500", 140, 64),
            new SeedProduct(11L, "FF-HO-0011", "Lampe de chevet LED doree",
                    "Abat-jour metal etroge 25cm", ProductCategories.HOME, "45.750", 210, null),
            new SeedProduct(12L, "FF-HO-0012", "Coussin coton 50x50 turquoise",
                    "Housse lavable, garnissage mousse", ProductCategories.HOME, "29.900", 3, null),
            new SeedProduct(13L, "FF-BE-0013", "Savon noir beldi 250g",
                    "Savon traditionnel a l'huile d'olive", ProductCategories.BEAUTY, "12.500", 160, 150),
            new SeedProduct(14L, "FF-BE-0014", "Shampoing creme 400ml",
                    "Cheveux secs et abimes, 400ml", ProductCategories.BEAUTY, "34.900", 65, 80),
            new SeedProduct(15L, "FF-BE-0015", "Huile d'argan pressee a froid 100ml",
                    "100% pure, usage alimentaire et cosmetique", ProductCategories.BEAUTY, "28.750", 98, null),
            new SeedProduct(16L, "FF-BE-0016", "Eau de parfum Tunis 100ml",
                    "Senteur fresh de fleur d'oranger", ProductCategories.BEAUTY, "79.000", 42, null),
            new SeedProduct(17L, "FF-SP-0017", "Tapis de yoga antidérapant 6mm",
                    "173 x 61 cm, PVC sans latex", ProductCategories.SPORTS, "79.900", 110, 70),
            new SeedProduct(18L, "FF-SP-0018", "Haltères réglables 2 x 10 kg",
                    "Disques interchangeables, poignees ergonomiques", ProductCategories.SPORTS, "249.000", 130, 55),
            new SeedProduct(19L, "FF-SP-0019", "Roue abdominale pliable",
                    "Roue de remise en forme avec poignees souples", ProductCategories.SPORTS, "69.500", 9, null),
            new SeedProduct(20L, "FF-ST-0020", "Ramette papier A4 80g x5",
                    "Papier blanc pour photocopie et impression", ProductCategories.STATIONERY, "39.900", 0, null));

    private final JdbcTemplate jdbcTemplate;

    public DemoDataSeeder(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        Long existing = jdbcTemplate.queryForObject(COUNT_PRODUCTS_SQL, Long.class);
        if (existing != null && existing > 0) {
            log.info("Warehouse seed skipped: {} products already present [correlationId={}]", existing,
                    CorrelationId.getOrCreate());
            return;
        }

        OffsetDateTime now = OffsetDateTime.ofInstant(Instant.now(), ZoneOffset.UTC);

        jdbcTemplate.batchUpdate(INSERT_WAREHOUSE_SQL, WAREHOUSES.stream()
                .map(warehouse -> new Object[] { warehouse.id(), warehouse.name(), warehouse.address(),
                        warehouse.city(), warehouse.capacity(), WarehouseStatus.ACTIVE.name(), now, now })
                .toList());

        jdbcTemplate.batchUpdate(INSERT_PRODUCT_SQL, PRODUCTS.stream()
                .map(product -> new Object[] { product.id(), product.sku(), product.name(), product.description(),
                        product.category(), new BigDecimal(product.price()), Boolean.TRUE, now, now })
                .toList());

        List<Object[]> stock = new ArrayList<>();
        for (SeedProduct product : PRODUCTS) {
            stock.add(new Object[] { TUNIS_WAREHOUSE_ID, product.id(), product.stockTunis(), 0, now });
            if (product.stockSousse() != null) {
                stock.add(new Object[] { SOUSSE_WAREHOUSE_ID, product.id(), product.stockSousse(), 0, now });
            }
        }
        jdbcTemplate.batchUpdate(INSERT_INVENTORY_SQL, stock);

        // Explicit ids do not advance the sequences, so the next generated id would
        // collide with the rows just inserted.
        jdbcTemplate.execute(RESET_WAREHOUSES_SEQUENCE_SQL);
        jdbcTemplate.execute(RESET_PRODUCTS_SEQUENCE_SQL);

        log.info("Seeded {} warehouses, {} products and {} stock levels [correlationId={}]",
                WAREHOUSES.size(), PRODUCTS.size(), stock.size(), CorrelationId.getOrCreate());
    }

    private record SeedWarehouse(Long id, String name, String address, String city, Integer capacity) {
    }

    private record SeedProduct(Long id, String sku, String name, String description, String category,
            String price, Integer stockTunis, Integer stockSousse) {
    }
}