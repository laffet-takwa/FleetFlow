# FleetFlow

Smart logistics and delivery platform — a microservices backend with a Vue 3 single-page
front end, covering customer ordering, warehouse inventory, last-mile delivery and live
tracking.

## Features

- **Storefront** — customers browse the catalogue, place orders and follow each delivery on a live map.
- **Operations console** — order pipeline, inventory levels, warehouses, drivers, vehicles, analytics.
- **Driver app** — assigned deliveries and status transitions along the enforced lifecycle
  `created → assigned → picked up → in transit → delivered`, with `failed` and `cancelled` branches,
  plus notifications.
- **Event-driven backbone** — order, inventory and delivery changes travel over Kafka; the tracking
  service streams position updates to the browser over Server-Sent Events.
- **Role-based access** — `ADMIN`, `OPERATIONS`, `DRIVER`, `CUSTOMER` with JWT authentication and
  per-role routing in the SPA.
- **Observability** — optional ELK stack (Elasticsearch, Logstash, Filebeat, Kibana) fed by every
  service, with correlation IDs threaded through logs, events and HTTP calls.

## Architecture

Seven domain services sit behind an API gateway, each owning its own data.

| Service | Port | Datastore | Responsibility |
|---|---|---|---|
| `api-gateway` | 8080 | — | Single entry point, JWT filter, routing, CORS |
| `auth-service` | 8081 | PostgreSQL `fleetflow_auth` | Registration, login, token issuance, identities |
| `customer-service` | 8082 | PostgreSQL `fleetflow_customer` | Customer profiles and addresses |
| `order-service` | 8083 | PostgreSQL `fleetflow_order` | Orders, items, status history |
| `warehouse-service` | 8084 | PostgreSQL `fleetflow_warehouse` | Catalogue, stock levels, warehouses, reservations |
| `delivery-service` | 8085 | PostgreSQL `fleetflow_delivery` | Drivers, vehicles, deliveries, assignment |
| `tracking-service` | 8086 | MongoDB `fleetflow_tracking`, Redis | Location history and the SSE stream |
| `notification-service` | 8087 | PostgreSQL `fleetflow_notification` | Notification feed, subscriptions |
| `discovery-server` | 8761 | — | Optional Eureka registry (`--profile discovery`) |

Supporting infrastructure: PostgreSQL 16, MongoDB 7, Redis 7, Kafka 3.8 (KRaft mode), and the
optional ELK stack.

```
                       ┌──────────────┐
   browser  ──────────▶│  api-gateway │──────────▶ auth / customer / order /
                       │    :8080     │             warehouse / delivery /
                       └──────┬───────┘             tracking / notification
                              │
                              ▼
                       ┌──────────────┐        ┌───────────────┐
                       │    Kafka     │◀──────▶│ event bus     │
                       │  order.*     │        │ topology      │
                       │  inventory.* │        └───────────────┘
                       │  delivery.*  │
                       └──────────────┘
                              │
                       ┌──────┴───────┐
                       │  SSE stream  │──▶ browser
                       └──────────────┘
```

The SPA only ever talks to the gateway, never to an individual service. In development Vite
proxies `/api` to `http://localhost:8080`; in a container nginx does the same.

## Tech stack

**Backend** — Java 17, Spring Boot 3.3.5, Spring Cloud 2023.0.3, Spring Security + JJWT,
Spring Data JPA, Flyway, springdoc-openapi, Maven.

**Frontend** — Vue 3.5 (`<script setup>`), TypeScript, Vite 5, Pinia, Vue Router, Tailwind CSS 3,
Leaflet, Axios.

## Prerequisites

- Docker with Compose v2 — the only requirement for the full stack
- JDK 17 and Maven 3.9+ — for running a backend service outside Docker
- Node.js 20+ — for running the SPA outside Docker

## Quick start

```bash
git clone https://github.com/laffet-takwa/FleetFlow.git
cd FleetFlow

cp .env.example .env          # optional: every value has a working default

docker compose up --build
```

This builds the images, starts the datastores, creates the Kafka topics, waits for every service to
report healthy and serves the SPA.

| Endpoint | URL |
|---|---|
| Web app | http://localhost:8088 |
| API gateway | http://localhost:8080 |
| Swagger UI (gateway routes) | http://localhost:8080/swagger-ui.html |
| Health | http://localhost:8080/actuator/health |

First build downloads Maven and npm dependencies and takes a while. Startup is sequential by design:
the gateway waits for all downstream services to be healthy.

To reset everything, including the seeded demo data:

```bash
docker compose down -v
```

## Demo accounts

Seeded on first start when `SEED_ENABLED=true`. Every account shares the password
**`Password123!`**.

| Role | Email |
|---|---|
| Admin | `admin@fleetflow.local` |
| Operations | `operations@fleetflow.local` |
| Driver | `driver1@fleetflow.local` … `driver5@fleetflow.local` |
| Customer | `customer1@fleetflow.local` … `customer10@fleetflow.local` |

Set `SEED_ENABLED=false` to start with empty databases and no demo identities. The seeders are
idempotent — they skip when data already exists.

## Local development

Run the datastores and infrastructure in Docker, then run the services and the SPA on the host for
fast rebuilds.

```bash
# infrastructure only
docker compose up -d postgres mongodb redis kafka kafka-init

# backend — build common first, then any service
mvn -f backend/pom.xml -pl common -am install -DskipTests
mvn -f backend/pom.xml -pl auth-service -am spring-boot:run

# frontend
cd frontend
npm install
npm run dev                  # http://localhost:5173
```

`backend/common` holds the shared kernel — error handling, correlation IDs, JWT, the Kafka envelope
and idempotency helpers. Everything else depends on it, so it must be installed to the local
repository before a service will build.

The Vite dev server proxies to the gateway. Override the target with `VITE_DEV_GATEWAY_URL` if the
gateway is not on port 8080.

### Verification

```bash
# backend
mvn -f backend/pom.xml test          # JUnit 5 unit tests, no infrastructure required

# frontend
cd frontend
npm run lint
npm run typecheck
npm run build
```

The backend suite is pure unit tests (Mockito collaborators, no Spring context), so it runs without
Docker or a running database.

## Configuration

All settings come from the environment. `.env` holds the local values; every key is documented in
`.env.example`.

| Variable | Default | Purpose |
|---|---|---|
| `DB_PASSWORD` | `fleetflow` | PostgreSQL password for all databases |
| `JWT_SECRET` | dev placeholder | Token signing key — **override outside local development** |
| `FLEETFLOW_INTERNAL_TOKEN` | dev placeholder | Guards `/internal/**` endpoints — **override outside local development** |
| `SEED_ENABLED` | `true` | Insert demo identities and reference data |
| `CORS_ALLOWED_ORIGINS` | `http://localhost:5173,http://localhost:8088,http://localhost:80` | Browser origins allowed to call the gateway |
| `LOG_LEVEL_COM_FLEETFLOW` | `INFO` | Log level for application code |
| `*_PORT` | see `.env.example` | Host port for each service |

Generate real secrets with `openssl rand -base64 48`.

## API surface

The gateway routes every public path:

| Prefix | Service |
|---|---|
| `/api/auth/**` | auth-service |
| `/api/customers/**` | customer-service |
| `/api/orders/**` | order-service |
| `/api/products/**`, `/api/inventory/**`, `/api/warehouses/**` | warehouse-service |
| `/api/drivers/**`, `/api/vehicles/**`, `/api/deliveries/**` | delivery-service |
| `/api/tracking/**` | tracking-service |
| `/api/notifications/**` | notification-service |

`/actuator/**` and `/swagger-ui/**` are exposed on the gateway for health checks and API browsing.
The gateway documents its own routes only; every service also serves its own OpenAPI UI on its own
port (`http://localhost:8081/swagger-ui.html` for `auth-service`, and so on).

## Event topology

Topics are declared explicitly by `infrastructure/kafka/01-create-topics.sh` so the topology is
reviewable and partition counts match the ordering requirements. Order-keyed topics get 6
partitions; low-volume administrative topics get 3.

| Topic | Partitions | Meaning |
|---|---|---|
| `order.created` | 6 | A new order was accepted |
| `inventory.reserved` | 6 | Stock was reserved for an order |
| `inventory.insufficient` | 3 | Reservation failed |
| `delivery.assigned` | 6 | A driver was assigned |
| `delivery.picked-up` | 6 | The driver collected the parcel |
| `delivery.started` | 6 | The delivery is in transit |
| `delivery.completed` | 6 | The delivery was delivered |
| `delivery.failed` | 3 | The delivery failed |
| `delivery.cancelled` | 3 | The delivery was cancelled |

Messages are JSON `EventEnvelope` records carrying an `eventId`, `eventType`, `topic`,
`correlationId`, `timestamp` and `payload`. Consumers de-duplicate on `eventId`.

## Observability

```bash
docker compose --profile elk up -d
```

Kibana is then available on http://localhost:5601. Filebeat reads the Docker log files through the
Docker socket and forwards them to Logstash, which indexes them as `fleetflow-logs-YYYY.MM.dd`.
Create a data view for that pattern the first time you open Kibana. Filter and set the log level with
`LOG_LEVEL_COM_FLEETFLOW`.

Correlation IDs are generated at the edge, echoed on responses via `X-Correlation-ID`, propagated into
event envelopes and restored inside consumers, so one request can be followed across every service.

## Project layout

```
FleetFlow/
├── backend/                   Maven aggregator (com.fleetflow)
│   ├── common/                shared kernel: errors, JWT, Kafka envelope, correlation IDs
│   ├── api-gateway/           entry point and routing
│   ├── auth-service/          identities and tokens
│   ├── customer-service/      customer profiles and addresses
│   ├── order-service/         orders and status history
│   ├── warehouse-service/     catalogue, inventory and reservations
│   ├── delivery-service/      drivers, vehicles, deliveries
│   ├── tracking-service/      location history and SSE
│   ├── notification-service/  notification feed
│   └── discovery-server/      optional Eureka registry
├── frontend/                  Vue 3 + Vite SPA
│   └── src/
│       ├── components/ui/     design-system primitives
│       ├── components/layout/ shell: header, sidebar, bell, theme toggle
│       ├── components/map/    Leaflet tracking map
│       ├── layouts/           Admin, Customer and Driver shells
│       ├── stores/            Pinia stores
│       ├── services/          typed API clients
│       └── router/            routes and role guards
├── infrastructure/
│   ├── docker/                Dockerfiles, nginx, Postgres bootstrap
│   ├── kafka/                 topic creation script
│   └── elk/                   Logstash pipeline and Filebeat config
├── docs/
│   ├── CONTRACTS.md           backend implementation contract
│   └── ui.md                  frontend design and engineering contract
└── docker-compose.yml         full local platform
```

## Documentation

- [`docs/CONTRACTS.md`](docs/CONTRACTS.md) — backend conventions: error handling, correlation IDs,
  JWT, Kafka envelopes, idempotency, and the API contract of every service. Read it before changing
  backend code.
- [`docs/ui.md`](docs/ui.md) — frontend conventions: design tokens, primitives, layouts, stores and
  the rules for building a view. Read it before adding a screen.

## License

Private project. All rights reserved.