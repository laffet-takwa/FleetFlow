CREATE TABLE orders (
    id               BIGSERIAL PRIMARY KEY,
    -- auth-service user id, i.e. the JWT subject; the identity space is shared
    -- with customer, driver and notification records.
    customer_id      BIGINT       NOT NULL,
    status           VARCHAR(30)  NOT NULL,
    subtotal         NUMERIC(12, 3) NOT NULL,
    delivery_fee     NUMERIC(12, 3) NOT NULL,
    total_amount     NUMERIC(12, 3) NOT NULL,
    currency         VARCHAR(3)   NOT NULL DEFAULT 'TND',
    delivery_address VARCHAR(255) NOT NULL,
    city             VARCHAR(80)  NOT NULL,
    postal_code      VARCHAR(16)  NOT NULL,
    -- populated once the delivery service allocates a delivery for this order
    delivery_id      BIGINT,
    cancelled_reason VARCHAR(255),
    created_at       TIMESTAMPTZ  NOT NULL,
    updated_at       TIMESTAMPTZ  NOT NULL
);

CREATE INDEX idx_orders_status ON orders (status);
CREATE INDEX idx_orders_customer_id ON orders (customer_id);
CREATE INDEX idx_orders_created_at ON orders (created_at);

COMMENT ON COLUMN orders.customer_id IS 'auth-service user id: the identity link carried by the JWT subject';
COMMENT ON COLUMN orders.status IS 'OrderStatus name; see the order status machine in docs/CONTRACTS.md';
