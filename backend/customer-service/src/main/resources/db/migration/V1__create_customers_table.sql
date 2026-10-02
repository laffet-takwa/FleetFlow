CREATE TABLE customers (
    id          BIGSERIAL PRIMARY KEY,
    user_id     BIGINT       NOT NULL UNIQUE,
    first_name  VARCHAR(80)  NOT NULL,
    last_name   VARCHAR(80)  NOT NULL,
    email       VARCHAR(180) NOT NULL,
    phone       VARCHAR(32)  NOT NULL,
    address     VARCHAR(255),
    city        VARCHAR(80),
    postal_code VARCHAR(16),
    created_at  TIMESTAMPTZ  NOT NULL,
    updated_at  TIMESTAMPTZ  NOT NULL
);

COMMENT ON COLUMN customers.user_id IS 'auth-service user id: the identity link carried by the JWT subject';
