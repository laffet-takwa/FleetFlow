CREATE TABLE order_items (
    id            BIGSERIAL PRIMARY KEY,
    order_id      BIGINT         NOT NULL REFERENCES orders (id),
    product_id    BIGINT         NOT NULL,
    -- product_name and unit_price are a deliberate snapshot taken at checkout:
    -- the catalogue may be renamed or repriced afterwards, but the order the
    -- customer agreed to must keep showing what it was placed with.
    product_name  VARCHAR(160)   NOT NULL,
    quantity      INT            NOT NULL CHECK (quantity > 0),
    unit_price    NUMERIC(12, 3) NOT NULL,
    line_subtotal NUMERIC(12, 3) NOT NULL,
    -- OrderItem extends the shared TimestampedEntity, so the mapped superclass
    -- columns have to exist here or ddl-auto=validate fails at boot.
    created_at     TIMESTAMPTZ  NOT NULL,
    updated_at     TIMESTAMPTZ  NOT NULL
);

CREATE INDEX idx_order_items_order_id ON order_items (order_id);
