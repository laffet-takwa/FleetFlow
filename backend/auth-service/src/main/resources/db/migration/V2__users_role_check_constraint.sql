-- Mirrors com.fleetflow.common.security.FleetRole. Downstream services authorize on
-- the role carried by the token, so the taxonomy is enforced at the storage boundary.
ALTER TABLE users
    ADD CONSTRAINT chk_users_role CHECK (role IN ('ADMIN', 'OPERATIONS', 'DRIVER', 'CUSTOMER'));