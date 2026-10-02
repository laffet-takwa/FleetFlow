-- One delivery per order: the unique constraint on order_id is the last line of
-- defence against a duplicated inventory.reserved event.
-- `customer_name` / `customer_phone` are a snapshot taken when the delivery was
-- created, so a driver never depends on customer-service being reachable.
CREATE TABLE deliveries
(
    id                BIGSERIAL PRIMARY KEY,
    order_id          BIGINT       NOT NULL,
    customer_id       BIGINT       NOT NULL,
    driver_id         BIGINT       REFERENCES drivers (id),
    vehicle_id        BIGINT       REFERENCES vehicles (id),
    status            VARCHAR(20)  NOT NULL,
    pickup_address    VARCHAR(255) NOT NULL,
    delivery_address  VARCHAR(255) NOT NULL,
    city              VARCHAR(80)  NOT NULL,
    postal_code       VARCHAR(16)  NOT NULL,
    customer_name     VARCHAR(120),
    customer_phone    VARCHAR(32),
    failure_reason    VARCHAR(255),
    proof_of_delivery VARCHAR(255),
    scheduled_at      TIMESTAMPTZ,
    started_at        TIMESTAMPTZ,
    completed_at      TIMESTAMPTZ,
    created_at        TIMESTAMPTZ  NOT NULL,
    updated_at        TIMESTAMPTZ  NOT NULL,
    CONSTRAINT uk_deliveries_order_id UNIQUE (order_id),
    CONSTRAINT ck_deliveries_status CHECK (status IN
        ('CREATED', 'ASSIGNED', 'PICKED_UP', 'IN_TRANSIT', 'DELIVERED', 'FAILED', 'CANCELLED'))
);

CREATE INDEX idx_deliveries_status ON deliveries (status);
CREATE INDEX idx_deliveries_driver_id ON deliveries (driver_id);
CREATE INDEX idx_deliveries_vehicle_id ON deliveries (vehicle_id);
CREATE INDEX idx_deliveries_created_at ON deliveries (created_at);
