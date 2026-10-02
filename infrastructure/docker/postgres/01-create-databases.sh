#!/bin/bash
#
# Creates one database per service on a single PostgreSQL instance.
#
# A separate instance per service would be closer to a real deployment, but it costs
# six containers and roughly a gigabyte of RAM for a local demo. Logical separation
# is preserved: each service owns its own database, user privileges stay inside it,
# and nothing crosses a schema boundary.
#
# The FIRST service to claim a database keeps ownership of it; the loop is therefore
# idempotent and safe to re-run.

set -euo pipefail

DATABASES=(
    "fleetflow_auth:fleetflow_auth"
    "fleetflow_customer:fleetflow_customer"
    "fleetflow_order:fleetflow_order"
    "fleetflow_warehouse:fleetflow_warehouse"
    "fleetflow_delivery:fleetflow_delivery"
    "fleetflow_notification:fleetflow_notification"
)

for entry in "${DATABASES[@]}"; do
    database="${entry%%:*}"
    owner="${entry##*:}"

    echo "==> ensuring database '${database}' owned by '${owner}'"
    psql -v ON_ERROR_STOP=1 --username "${POSTGRES_USER}" --dbname postgres <<-EOSQL
        SELECT format('CREATE DATABASE %I OWNER %I', '${database}', '${owner}')
        WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = '${database}')
        \gexec

        SELECT format('GRANT ALL PRIVILEGES ON DATABASE %I TO %I', '${database}', '${owner}')
        \gexec
EOSQL
done

echo "==> all FleetFlow databases are ready"
