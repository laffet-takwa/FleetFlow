# Creates the six per-service roles, each owning exactly one database.
#
# Roles are created in the image's own bootstrap phase (before any of the
# application services start), so every service connects as a user that is not the
# cluster superuser.

set -euo pipefail

ROLES=(
    fleetflow_auth
    fleetflow_customer
    fleetflow_order
    fleetflow_warehouse
    fleetflow_delivery
    fleetflow_notification
)

for role in "${ROLES[@]}"; do
    echo "==> ensuring role '${role}'"
    psql -v ON_ERROR_STOP=1 --username "${POSTGRES_USER}" --dbname postgres <<-EOSQL
        SELECT format('CREATE ROLE %I LOGIN PASSWORD %L', '${role}', '${POSTGRES_PASSWORD}')
        WHERE NOT EXISTS (SELECT FROM pg_roles WHERE rolname = '${role}')
        \gexec
EOSQL
done

echo "==> all FleetFlow roles are ready"
