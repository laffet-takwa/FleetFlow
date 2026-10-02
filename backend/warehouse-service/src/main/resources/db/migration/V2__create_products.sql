CREATE TABLE products (
    id          BIGSERIAL PRIMARY KEY,
    sku         VARCHAR(48)  NOT NULL UNIQUE,
    name        VARCHAR(160) NOT NULL,
    description VARCHAR(500),
    category    VARCHAR(60)  NOT NULL,
    -- TND, so three decimals: NUMERIC(12, 3) must match the entity mapping exactly.
    price       NUMERIC(12, 3) NOT NULL CHECK (price >= 0),
    active      BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMPTZ  NOT NULL,
    updated_at  TIMESTAMPTZ  NOT NULL
);

CREATE INDEX idx_products_category ON products (category);
CREATE INDEX idx_products_active ON products (active);

COMMENT ON COLUMN products.category IS
    'One of GROCERY, ELECTRONICS, HOME, BEAUTY, SPORTS, STATIONERY';