-- Shared table used by every Kafka consumer through
-- com.fleetflow.common.idempotency.IdempotencyService.
-- Shipped with fleetflow-common and applied from
-- classpath:com/fleetflow/common/db/migration (see spring.flyway.locations).
CREATE TABLE IF NOT EXISTS processed_event
(
    event_id     VARCHAR(64) PRIMARY KEY,
    event_type   VARCHAR(64)  NOT NULL,
    processed_at TIMESTAMP    NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_processed_event_processed_at ON processed_event (processed_at);