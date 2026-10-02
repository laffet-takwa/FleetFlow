CREATE TABLE inventory_reservation (
    id           BIGSERIAL PRIMARY KEY,
    order_id     BIGINT      NOT NULL,
    product_id   BIGINT      NOT NULL REFERENCES products (id),
    warehouse_id BIGINT      NOT NULL REFERENCES warehouses (id),
    quantity     INT         NOT NULL CHECK (quantity > 0),
    created_at   TIMESTAMPTZ NOT NULL,
    -- This is what makes a reservation idempotent per order line: a redelivered
    -- order.created that slipped past IdempotencyService cannot double insert.
    CONSTRAINT uq_inventory_reservation_order_product UNIQUE (order_id, product_id)
);

CREATE INDEX idx_inventory_reservation_order_id ON inventory_reservation (order_id);
CREATE INDEX idx_inventory_reservation_warehouse_id ON inventory_reservation (warehouse_id);