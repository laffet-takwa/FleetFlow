package com.fleetflow.order.seed;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.fleetflow.common.correlation.CorrelationId;

import com.fleetflow.order.entity.OrderStatus;
import com.fleetflow.order.entity.StatusSource;

/**
 * Fills the order book with a fortnight of plausible activity so the operations
 * dashboard, the customer order list and the analytics chart all have something to
 * show on a fresh database.
 *
 * <p>Ids are inserted explicitly so the three tables can be reset deterministically,
 * and a fixed seed keeps the demo identical on every machine.
 */
@Component
@ConditionalOnProperty(name = "fleetflow.seed.enabled", havingValue = "true")
public class DemoDataSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoDataSeeder.class);

    private static final int ORDER_COUNT = 20;
    private static final int SEED_DAYS = 14;
    private static final long RANDOM_SEED = 20260902L;
    private static final BigDecimal DELIVERY_FEE = new BigDecimal("8.00");
    private static final String CURRENCY = "TND";

    private static final String COUNT_SQL = "SELECT COUNT(*) FROM orders";
    private static final String INSERT_ORDER_SQL = """
            INSERT INTO orders (id, customer_id, status, subtotal, delivery_fee, total_amount, currency,
                                delivery_address, city, postal_code, delivery_id, cancelled_reason,
                                created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;
    private static final String INSERT_ITEM_SQL = """
            INSERT INTO order_items (id, order_id, product_id, product_name, quantity, unit_price,
                                     line_subtotal, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;
    private static final String INSERT_HISTORY_SQL = """
            INSERT INTO order_status_history (id, order_id, status, previous_status, source, note, changed_at)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            """;
    private static final String RESET_ORDERS_SEQUENCE_SQL =
            "SELECT setval('orders_id_seq', (SELECT MAX(id) FROM orders))";
    private static final String RESET_ITEMS_SEQUENCE_SQL =
            "SELECT setval('order_items_id_seq', (SELECT MAX(id) FROM order_items))";
    private static final String RESET_HISTORY_SEQUENCE_SQL =
            "SELECT setval('order_status_history_id_seq', (SELECT MAX(id) FROM order_status_history))";

    /** Mirrors the warehouse catalogue so the seeded lines read like real products. */
    private static final List<SeedProduct> PRODUCTS = List.of(
            new SeedProduct(1L, "Carton 6 x Brik UHT 1L", "8.500"),
            new SeedProduct(2L, "Huile d'olive extra vierge 1L", "34.900"),
            new SeedProduct(3L, "Cafe moulu Arabe 250g", "12.750"),
            new SeedProduct(4L, "The vert a la menthe 100 sachets", "9.300"),
            new SeedProduct(5L, "Lentilles corail 1kg", "6.400"),
            new SeedProduct(6L, "Semoule fine 1kg", "3.950"),
            new SeedProduct(7L, "Huile de table 5L", "41.500"),
            new SeedProduct(8L, "Farine de blÃ© 10kg", "38.000"),
            new SeedProduct(9L, "Sucre en poudre 1kg", "4.200"),
            new SeedProduct(10L, "Biscuits fourrÃ©s 300g", "5.600"),
            new SeedProduct(11L, "Jus d'orange 1L", "4.800"),
            new SeedProduct(12L, "Eau minerale 1.5L x6", "6.900"),
            new SeedProduct(13L, "Ciment 50kg", "12.500"),
            new SeedProduct(14L, "Peinture acrylique 10L", "129.000"),
            new SeedProduct(15L, "Cables electriques 2.5mm x100m", "58.000"),
            new SeedProduct(16L, "Peinture blanche 20kg", "215.000"),
            new SeedProduct(17L, "Tuyau PVC 40mm x4m", "27.500"),
            new SeedProduct(18L, "Robinet mitigeur chromÃ©", "74.000"),
            new SeedProduct(19L, "Serrure Ã  cylindre 3 points", "96.000"),
            new SeedProduct(20L, "Interrupteur(double allumage)", "18.750"));

    /** Tunis area addresses, matched with a plausible postal code per city. */
    private static final List<SeedAddress> ADDRESSES = List.of(
            new SeedAddress("12 Rue Habib Bourguiba", "Tunis", "1000"),
            new SeedAddress("45 Avenue Taieb Mhiri", "Tunis", "1002"),
            new SeedAddress("8 Avenue de la Liberte", "Tunis", "1003"),
            new SeedAddress("30 Rue Mohamed Slim", "Tunis", "1011"),
            new SeedAddress("21 Boulevard Yasser Arafat", "Ariana", "1014"),
            new SeedAddress("7 Rue du 14 Janvier 2011", "Ben Arous", "2013"),
            new SeedAddress("26 Avenue Ibn El Jazzar", "Tunis", "1002"),
            new SeedAddress("9 Rue d'Espagne", "Tunis", "1001"),
            new SeedAddress("15 Rue de l'Universite", "Tunis", "1010"),
            new SeedAddress("3 Rue Al-Qods", "Sousse", "4000"),
            new SeedAddress("41 Avenue Taieb Mhiri", "Sfax", "3000"),
            new SeedAddress("18 Rue des Martyrs", "Nabeul", "8000"));

    /** auth-service user ids 8..17 are the seeded customers. */
    private static final long FIRST_CUSTOMER_ID = 8L;
    private static final long CUSTOMER_COUNT = 10L;

    /**
     * Delivered orders dominate a real book, so the generated statuses are weighted
     * rather than uniform; the earlier a day, the more likely the order is finished.
     */
    private static final OrderStatus[] DELIVERED = {OrderStatus.DELIVERED};
    private static final OrderStatus[] MOSTLY_DELIVERED = {
            OrderStatus.DELIVERED, OrderStatus.DELIVERED, OrderStatus.CANCELLED, OrderStatus.OUT_FOR_DELIVERY};
    private static final OrderStatus[] IN_FLIGHT = {
            OrderStatus.READY_FOR_DELIVERY, OrderStatus.PROCESSING, OrderStatus.CONFIRMED, OrderStatus.CREATED};
    private static final OrderStatus[] MIXED = {
            OrderStatus.CREATED, OrderStatus.CONFIRMED, OrderStatus.PROCESSING,
            OrderStatus.READY_FOR_DELIVERY, OrderStatus.OUT_FOR_DELIVERY, OrderStatus.CANCELLED};

    private final JdbcTemplate jdbcTemplate;

    public DemoDataSeeder(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        Long existing = jdbcTemplate.queryForObject(COUNT_SQL, Long.class);
        if (existing != null && existing > 0) {
            log.info("Order seed skipped: {} rows already present [correlationId={}]", existing,
                    CorrelationId.getOrCreate());
            return;
        }

        Random random = new Random(RANDOM_SEED);
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        long itemId = 1L;
        long historyId = 1L;
        List<Object[]> orders = new ArrayList<>(ORDER_COUNT);
        List<Object[]> items = new ArrayList<>();
        List<Object[]> history = new ArrayList<>();

        for (int index = 0; index < ORDER_COUNT; index++) {
            long orderId = index + 1L;
            // Day 0 is today, so older orders land further back in the fortnight.
            int daysAgo = random.nextInt(SEED_DAYS);
            Instant createdAt = today.minusDays(daysAgo)
                    .atTime(8 + random.nextInt(10), random.nextInt(60))
                    .toInstant(ZoneOffset.UTC);
            OrderStatus target = pickStatus(daysAgo, random);

            List<SeedLine> lines = pickLines(random);
            BigDecimal subtotal = BigDecimal.ZERO;
            for (SeedLine line : lines) {
                subtotal = subtotal.add(line.lineSubtotal());
            }
            subtotal = subtotal.setScale(3, RoundingMode.HALF_UP);
            BigDecimal total = subtotal.add(DELIVERY_FEE);
            SeedAddress address = ADDRESSES.get(random.nextInt(ADDRESSES.size()));
            long customerId = FIRST_CUSTOMER_ID + random.nextInt((int) CUSTOMER_COUNT);

            List<OrderStatus> chain = historyChain(target);
            String cancelledReason = target == OrderStatus.CANCELLED
                    ? (random.nextBoolean() ? "Out of stock for one of the lines" : "Customer changed their mind")
                    : null;

            // The PostgreSQL driver cannot infer a SQL type for java.time.Instant when
            // binding through JDBC, so it is converted to OffsetDateTime here. Hibernate
            // handles Instant itself; only this hand-written INSERT needs the conversion.
            java.time.OffsetDateTime createdAtUtc =
                    java.time.OffsetDateTime.ofInstant(createdAt, ZoneOffset.UTC);

            orders.add(new Object[] { orderId, customerId, target.name(), subtotal, DELIVERY_FEE, total,
                    CURRENCY, address.address(), address.city(), address.postalCode(),
                    deliveryIdFor(target, orderId), cancelledReason, createdAtUtc, createdAtUtc });

            for (SeedLine line : lines) {
                items.add(new Object[] { itemId++, orderId, line.product().id(), line.product().name(),
                        line.quantity(), line.unitPrice(), line.lineSubtotal(), createdAtUtc, createdAtUtc });
            }

            for (int step = 0; step < chain.size(); step++) {
                OrderStatus previous = step == 0 ? null : chain.get(step - 1);
                history.add(new Object[] { historyId++, orderId, chain.get(step).name(),
                        previous == null ? null : previous.name(),
                        sourceFor(step, chain).name(), noteFor(chain.get(step)),
                        java.time.OffsetDateTime.ofInstant(changedAt(createdAt, step), ZoneOffset.UTC) });
            }
        }

        jdbcTemplate.batchUpdate(INSERT_ORDER_SQL, orders);
        jdbcTemplate.batchUpdate(INSERT_ITEM_SQL, items);
        jdbcTemplate.batchUpdate(INSERT_HISTORY_SQL, history);
        // Explicit ids do not advance the sequences, so the next generated id would
        // collide with the rows just inserted.
        jdbcTemplate.execute(RESET_ORDERS_SEQUENCE_SQL);
        jdbcTemplate.execute(RESET_ITEMS_SEQUENCE_SQL);
        jdbcTemplate.execute(RESET_HISTORY_SEQUENCE_SQL);

        log.info("Seeded {} orders, {} items and {} timeline rows [correlationId={}]",
                orders.size(), items.size(), history.size(), CorrelationId.getOrCreate());
    }

    private OrderStatus pickStatus(int daysAgo, Random random) {
        OrderStatus[] pool;
        if (daysAgo <= 3) {
            pool = IN_FLIGHT;
        } else if (daysAgo <= 7) {
            pool = MOSTLY_DELIVERED;
        } else if (daysAgo <= 10) {
            pool = DELIVERED;
        } else {
            pool = MIXED;
        }
        return pool[random.nextInt(pool.length)];
    }

    /** A cancelled order is cancelled from a state the machine can actually reach. */
    private static List<OrderStatus> historyChain(OrderStatus target) {
        List<OrderStatus> chain = new ArrayList<>();
        chain.add(OrderStatus.CREATED);
        if (target == OrderStatus.CANCELLED) {
            chain.add(OrderStatus.CONFIRMED);
            chain.add(OrderStatus.CANCELLED);
            return chain;
        }
        if (target == OrderStatus.CONFIRMED) {
            return chain;
        }
        chain.add(OrderStatus.CONFIRMED);
        if (target == OrderStatus.PROCESSING) {
            return chain;
        }
        chain.add(OrderStatus.PROCESSING);
        if (target == OrderStatus.READY_FOR_DELIVERY) {
            return chain;
        }
        chain.add(OrderStatus.READY_FOR_DELIVERY);
        if (target == OrderStatus.OUT_FOR_DELIVERY) {
            return chain;
        }
        chain.add(OrderStatus.OUT_FOR_DELIVERY);
        chain.add(OrderStatus.DELIVERED);
        return chain;
    }

    /** Only orders that reached a delivery own one; a created order has none yet. */
    private static Object deliveryIdFor(OrderStatus status, long orderId) {
        return switch (status) {
            case PROCESSING, READY_FOR_DELIVERY, OUT_FOR_DELIVERY, DELIVERED -> 500L + orderId;
            default -> null;
        };
    }

    /** The opening row is the customer's; the rest came from events, except a staff cancellation. */
    private static StatusSource sourceFor(int step, List<OrderStatus> chain) {
        if (step == 0) {
            return StatusSource.CUSTOMER;
        }
        if (chain.get(step) == OrderStatus.CANCELLED) {
            return StatusSource.OPERATIONS;
        }
        return StatusSource.SYSTEM;
    }

    private static String noteFor(OrderStatus status) {
        return switch (status) {
            case CREATED -> "Order placed";
            case CONFIRMED -> "Inventory reserved";
            case PROCESSING -> "Driver and vehicle assigned";
            case READY_FOR_DELIVERY -> "Goods ready for delivery";
            case OUT_FOR_DELIVERY -> "Driver en route";
            case DELIVERED -> "Delivered";
            case CANCELLED -> "Cancelled before dispatch";
        };
    }

    /** Two hour gaps keep the timeline plausible without ordering rows within a day. */
    private static Instant changedAt(Instant createdAt, int step) {
        return createdAt.plusSeconds(step * 2L * 3600L);
    }

    private static List<SeedLine> pickLines(Random random) {
        int count = 1 + random.nextInt(4);
        List<SeedLine> lines = new ArrayList<>(count);
        List<SeedProduct> pool = new ArrayList<>(PRODUCTS);
        for (int i = 0; i < count; i++) {
            SeedProduct product = pool.remove(random.nextInt(pool.size()));
            int quantity = 1 + random.nextInt(3);
            BigDecimal unitPrice = new BigDecimal(product.price());
            BigDecimal lineSubtotal = unitPrice.multiply(BigDecimal.valueOf(quantity))
                    .setScale(3, RoundingMode.HALF_UP);
            lines.add(new SeedLine(product, quantity, unitPrice, lineSubtotal));
        }
        return lines;
    }

    private record SeedProduct(Long id, String name, String price) {
    }

    private record SeedAddress(String address, String city, String postalCode) {
    }

    private record SeedLine(SeedProduct product, int quantity, BigDecimal unitPrice, BigDecimal lineSubtotal) {
    }
}
