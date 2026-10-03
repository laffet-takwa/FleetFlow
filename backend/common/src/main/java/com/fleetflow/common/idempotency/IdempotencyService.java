package com.fleetflow.common.idempotency;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Guards Kafka consumers against duplicate delivery.
 *
 * <p>Kafka guarantees at-least-once delivery, so the same {@code eventId} can arrive
 * more than once (broker restart, consumer rebalance, retry after a crash). Every
 * consumer wraps its handler with {@link #executeOnce}, which records the event id
 * in {@code processed_event} after the handler succeeded and skips it otherwise.
 *
 * <p>The record is written with {@code ON CONFLICT DO NOTHING}, so the check is a
 * single atomic statement and needs no distributed lock.
 */
public class IdempotencyService {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyService.class);

    private static final String INSERT_SQL =
            "INSERT INTO processed_event (event_id, event_type, processed_at) VALUES (?, ?, ?) "
                    + "ON CONFLICT (event_id) DO NOTHING";
    // COUNT(*) rather than EXISTS or a bare "SELECT 1": JdbcTemplate.queryForObject
    // throws EmptyResultDataAccessException when a query returns no rows, so an EXISTS
    // check would blow up on the very first event a consumer sees — which is every
    // consumer, and would block the whole event flow.
    private static final String COUNT_SQL = "SELECT COUNT(*) FROM processed_event WHERE event_id = ?";
    private static final String DELETE_SQL = "DELETE FROM processed_event WHERE processed_at < ?";

    private final JdbcTemplate jdbcTemplate;
    private final AtomicLong invocations = new AtomicLong();

    /** Purge runs opportunistically roughly once per 1000 processed events. */
    private static final long PURGE_INTERVAL = 1_000L;
    private final Duration retention;

    public IdempotencyService(JdbcTemplate jdbcTemplate, Duration retention) {
        this.jdbcTemplate = jdbcTemplate;
        this.retention = retention;
    }

    public boolean wasProcessed(String eventId) {
        Integer count = jdbcTemplate.queryForObject(COUNT_SQL, Integer.class, eventId);
        return count != null && count > 0;
    }

    /** @return {@code true} the first time this event id is recorded. */
    public boolean markProcessed(String eventId, String eventType) {
        int rows = jdbcTemplate.update(INSERT_SQL, eventId, eventType, Timestamp.from(Instant.now()));
        if (rows > 0 && invocations.incrementAndGet() % PURGE_INTERVAL == 0) {
            purgeOlderThan(retention);
        }
        return rows > 0;
    }

    /**
     * Runs {@code action} only the first time {@code eventId} is seen.
     *
     * @return {@code true} when the action actually ran
     */
    public boolean executeOnce(String eventId, String eventType, Runnable action) {
        if (wasProcessed(eventId)) {
            log.debug("Skipping already processed event {} ({})", eventId, eventType);
            return false;
        }
        action.run();
        markProcessed(eventId, eventType);
        return true;
    }

    public int purgeOlderThan(Duration age) {
        int removed = jdbcTemplate.update(DELETE_SQL, Timestamp.from(Instant.now().minus(age)));
        if (removed > 0) {
            log.debug("Purged {} processed_event rows older than {}", removed, age);
        }
        return removed;
    }
}