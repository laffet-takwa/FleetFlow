CREATE TABLE inventory (
    id                 BIGSERIAL PRIMARY KEY,
    warehouse_id       BIGINT      NOT NULL REFERENCES warehouses (id),
    product_id         BIGINT      NOT NULL REFERENCES products (id),
    available_quantity INT         NOT NULL DEFAULT 0 CHECK (available_quantity >= 0),
    reserved_quantity  INT         NOT NULL DEFAULT 0 CHECK (reserved_quantity >= 0),
    -- Deliberately no created_at: this row is a mutable level, not an audit record,
    -- so it does not extend TimestampedEntity and owns only updated_at.
    updated_at         TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_inventory_warehouse_product UNIQUE (warehouse_id, product_id)
);

CREATE INDEX idx_inventory_warehouse_id ON inventory (warehouse_id);
CREATE INDEX idx_inventory_product_id ON inventory (product_id);

COMMENT ON TABLE inventory IS
    'One row per warehouse/product pair; available and reserved must never go negative';