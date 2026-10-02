-- Drivers are the delivery fleet. `user_id` is the auth-service subject (JWT `sub`),
-- 3..7 for the seeded demo accounts, so it is unique across the platform.
CREATE TABLE drivers
(
    id             BIGSERIAL PRIMARY KEY,
    user_id        BIGINT       NOT NULL,
    full_name      VARCHAR(120) NOT NULL,
    license_number VARCHAR(40)  NOT NULL,
    phone          VARCHAR(32)  NOT NULL,
    status         VARCHAR(20)  NOT NULL,
    created_at     TIMESTAMPTZ  NOT NULL,
    updated_at     TIMESTAMPTZ  NOT NULL,
    CONSTRAINT uk_drivers_user_id UNIQUE (user_id),
    CONSTRAINT uk_drivers_license_number UNIQUE (license_number),
    CONSTRAINT ck_drivers_status CHECK (status IN ('AVAILABLE', 'ON_DELIVERY', 'OFFLINE'))
);

CREATE INDEX idx_drivers_status ON drivers (status);
