-- Vehicles are shared across deliveries; `capacity` is the payload limit in kilograms.
CREATE TABLE vehicles
(
    id                  BIGSERIAL PRIMARY KEY,
    registration_number VARCHAR(20) NOT NULL,
    type                VARCHAR(40) NOT NULL,
    capacity            INT        NOT NULL,
    status              VARCHAR(20) NOT NULL,
    created_at          TIMESTAMPTZ NOT NULL,
    updated_at          TIMESTAMPTZ NOT NULL,
    CONSTRAINT uk_vehicles_registration_number UNIQUE (registration_number),
    CONSTRAINT ck_vehicles_type CHECK (type IN ('VAN', 'MOTORCYCLE', 'TRUCK', 'CAR')),
    CONSTRAINT ck_vehicles_status CHECK (status IN ('AVAILABLE', 'IN_USE', 'MAINTENANCE'))
);

CREATE INDEX idx_vehicles_status ON vehicles (status);
CREATE INDEX idx_vehicles_type ON vehicles (type);
