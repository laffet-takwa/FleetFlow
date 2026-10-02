-- Append only: this is the timeline rendered by the order details screen, and it
-- is the audit trail for every automatic transition driven by a Kafka event.
CREATE TABLE order_status_history (
    id              BIGSERIAL PRIMARY KEY,
    order_id        BIGINT       NOT NULL REFERENCES orders (id),
    status          VARCHAR(30)  NOT NULL,
    previous_status VARCHAR(30),
    source          VARCHAR(20)  NOT NULL,
    note            VARCHAR(255),
    changed_at      TIMESTAMPTZ  NOT NULL
);

CREATE INDEX idx_order_status_history_order_id ON order_status_history (order_id);

COMMENT ON COLUMN order_status_history.source IS 'CUSTOMER, OPERATIONS or SYSTEM: who caused the transition';
