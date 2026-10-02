-- Identity store for the whole platform: customerId, driver userId and
-- notification userId all refer to a row in this table (docs/CONTRACTS.md S5).
CREATE TABLE users
(
    id            BIGSERIAL PRIMARY KEY,
    first_name    VARCHAR(80)  NOT NULL,
    last_name     VARCHAR(80)  NOT NULL,
    email         VARCHAR(180) NOT NULL,
    phone         VARCHAR(32)  NOT NULL,
    password_hash VARCHAR(100) NOT NULL,
    address       VARCHAR(255),
    role          VARCHAR(20)  NOT NULL,
    enabled       BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at    TIMESTAMPTZ  NOT NULL,
    updated_at    TIMESTAMPTZ  NOT NULL,

    CONSTRAINT uq_users_email UNIQUE (email)
);

-- Staff screens filter by role before paging, and login reads a single row by email.
CREATE INDEX idx_users_role ON users (role);