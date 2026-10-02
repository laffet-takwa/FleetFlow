CREATE TABLE warehouses (
    id         BIGSERIAL PRIMARY KEY,
    name       VARCHAR(120) NOT NULL UNIQUE,
    address    VARCHAR(255) NOT NULL,
    city       VARCHAR(80)  NOT NULL,
    capacity   INT          NOT NULL CHECK (capacity > 0),
    -- WarehouseStatus name; see the enum rather than a lookup table, the set is closed.
    status     VARCHAR(20)  NOT NULL,
    -- Warehouse extends the shared TimestampedEntity, so both columns must exist
    -- here or ddl-auto=validate fails at boot.
    created_at TIMESTAMPTZ  NOT NULL,
    updated_at TIMESTAMPTZ  NOT NULL
);

CREATE INDEX idx_warehouses_status ON warehouses (status);

COMMENT ON COLUMN warehouses.status IS 'WarehouseStatus name: ACTIVE or INACTIVE';