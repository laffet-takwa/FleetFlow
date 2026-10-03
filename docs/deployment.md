# FleetFlow Deployment

Three supported targets, in increasing order of realism: a local IDE run, Docker
Compose, and Kubernetes. The same images and the same Flyway migrations run in all
three.

---

## 1. Local, no containers

Runs the services on the host and expects the datastores to be reachable. Useful for a
fast inner loop with an IDE.

**Prerequisites**

* JDK 17
* Maven 3.9+
* Node 22+ (for the SPA)
* PostgreSQL 16, MongoDB 7, Redis 7, Kafka 3.8 (KRaft)

**Start the datastores**, or just the datastores from Compose:

```bash
docker compose up -d postgres mongodb redis kafka kafka-init
```

**Start the backend.** Each service defaults to `localhost`, so no configuration is
needed:

```bash
mvn -f backend/pom.xml -pl auth-service spring-boot:run
```

Repeat per service, or build once and run the jars:

```bash
mvn -f backend/pom.xml clean install -DskipTests
java -jar backend/order-service/target/fleetflow-order-service-1.0.0.jar
```

| Service | Port |
|---|---|
| api-gateway | 8080 |
| auth-service | 8081 |
| customer-service | 8082 |
| order-service | 8083 |
| warehouse-service | 8084 |
| delivery-service | 8085 |
| tracking-service | 8086 |
| notification-service | 8087 |

**Start the frontend:**

```bash
cd frontend
npm install
npm run dev        # http://localhost:5173
```

The dev server proxies `/api` to `http://localhost:8080`, so the browser sees one
origin and CORS never enters the picture.

---

## 2. Docker Compose — the documented path

```bash
cp .env.example .env          # adjust if you want to
docker compose up --build
```

First start builds eight JVM images, which takes a while. After that, `up` is a few
seconds.

| Endpoint | URL |
|---|---|
| Web application | http://localhost:8088 |
| API gateway | http://localhost:8080 |
| Kafka UI (raw broker) | `localhost:29092` |
| PostgreSQL | `localhost:5432` |
| MongoDB | `localhost:27017` |
| Redis | `localhost:6379` |

```bash
docker compose up --build -d              # background
docker compose ps                         # health and ports
docker compose logs -f order-service      # follow one service
docker compose down -v                    # stop and delete all data
```

**Profiles.** Two optional stacks, off by default so a plain `up` starts only what the
application needs:

```bash
docker compose --profile elk up -d        # Elasticsearch + Logstash + Kibana + Filebeat
docker compose --profile discovery up -d  # the optional Eureka registry
```

Kibana is then at http://localhost:5601. The whole reason the ELK profile is optional is
resource usage: Elasticsearch alone wants about a gigabyte, which is a poor trade for a
developer machine that is not demonstrating observability.

**Verify the flow works**, rather than assuming it does:

```bash
./scripts/verify-e2e.sh
```

---

## 3. Environment variables

Nothing is hard-coded; every value below is read from the environment with a local
default. See `.env.example`.

| Variable | Default | Used by |
|---|---|---|
| `SERVER_PORT` | per service | each service |
| `DB_HOST` / `DB_PORT` | `localhost` / `5432` | six services |
| `DB_NAME` | `fleetflow_<service>` | six services |
| `DB_USERNAME` / `DB_PASSWORD` | `fleetflow` | six services |
| `KAFKA_BOOTSTRAP_SERVERS` | `localhost:9092` | four services |
| `REDIS_HOST` / `REDIS_PORT` | `localhost` / `6379` | tracking |
| `MONGODB_URI` | `mongodb://localhost:27017/fleetflow_tracking` | tracking |
| `JWT_SECRET` | local development value | **all** |
| `JWT_ACCESS_TOKEN_TTL` | `PT1H` | all |
| `FLEETFLOW_INTERNAL_TOKEN` | local development value | all |
| `AUTH_SERVICE_URL` … `NOTIFICATION_SERVICE_URL` | `http://localhost:<port>` | gateway, and the callers that need them |
| `CORS_ALLOWED_ORIGINS` | `http://localhost:5173` | gateway |
| `SEED_ENABLED` | `true` | six services |
| `JPA_DDL_AUTO` | `validate` | six services |
| `TRACKING_LOCATION_TTL` | `PT6H` | tracking |

`JWT_SECRET` and `FLEETFLOW_INTERNAL_TOKEN` are the two that matter. Both have a
development default that makes a fresh clone work; neither may keep it outside a laptop.
Generate real ones with:

```bash
openssl rand -base64 48      # JWT_SECRET
openssl rand -hex 32         # FLEETFLOW_INTERNAL_TOKEN
```

`JWT_SECRET` must be identical across the gateway and every service: the gateway
verifies what the auth service signed.

---

## 4. Docker images

```bash
# One service
docker build -f infrastructure/docker/Dockerfile.backend \
  --build-arg MODULE=order-service -t fleetflow/order-service:1.0.0 .

# The SPA
docker build -f infrastructure/docker/Dockerfile.frontend -t fleetflow/frontend:1.0.0 .
```

The backend image is a two-stage build: Maven compiles, a JRE runs the result. Layer
caching puts the dependency download behind the POM change, so source edits do not
re-resolve the world. The runtime stage installs only `curl` (for the health check),
runs as a non-root user, and drops every Linux capability.

---

## 5. Kubernetes

### Order of operations

```bash
# 1. Registry (kind, minikube or a real cluster)
minikube start

# 2. Images, loaded into the local registry
for m in api-gateway auth-service customer-service order-service \
         warehouse-service delivery-service tracking-service notification-service; do
  docker build -f infrastructure/docker/Dockerfile.backend \
    --build-arg MODULE=$m -t fleetflow/$m:1.0.0 .
  minikube image load fleetflow/$m:1.0.0
done

# 3. Secrets — never the committed placeholders
$jwt  = [Convert]::ToBase64String((1..48 | ForEach-Object { Get-Random -Maximum 256 }))
$token = -join ((1..64) | ForEach-Object { '{0:x}' -f (Get-Random -Maximum 16) })
kubectl -n fleetflow create secret generic fleetflow-secrets \
  --from-literal=JWT_SECRET=$jwt \
  --from-literal=FLEETFLOW_INTERNAL_TOKEN=$token \
  --from-literal=DB_PASSWORD=fleetflow \
  --from-literal=POSTGRES_ADMIN_USER=fleetflow \
  --dry-run=client -o yaml | kubectl apply -f -

# 4. Everything else
kubectl apply -k infrastructure/kubernetes

# 5. Watch it come up
kubectl -n fleetflow get pods -w
kubectl -n fleetflow rollout status deploy/api-gateway
```

`kubectl kustomize infrastructure/kubernetes` renders the whole stack.

### What the manifests do

* **Namespace** `fleetflow` with a ResourceQuota and Pod Security Admission at
  `baseline`.
* **ConfigMap** for non-sensitive settings, **Secret** for credentials. Cross-service
  URLs are cluster DNS names, which is why no service discovery server is needed.
* **Deployment + Service** per service: 2 replicas, rolling update with
  `maxUnavailable: 0`, resource requests and limits (Guaranteed QoS), a non-root
  user, a read-only root filesystem with an `emptyDir` at `/tmp`, and all capabilities
  dropped.
* **Probes** on the actuator groups. Readiness includes the datastores; liveness does
  not, because a database outage must stop traffic arriving without restarting every
  replica in the namespace and turning degradation into an outage.
* **Ingress** exposing only the gateway, with `proxy-buffering: off` and long read
  timeouts so the SSE streams are not buffered or cut.
* **StatefulSet** for PostgreSQL and MongoDB, **Deployment** for Redis and Kafka, plus
  a **Job** that creates the nine topics with explicit partition counts.

### Known cluster limitations

* One replica each for PostgreSQL, MongoDB, Redis and Kafka, and `emptyDir` for Kafka
  and Redis. **Restarting a pod loses that data.** The manifests do not pretend
  otherwise: the storage declarations are commented out where they would imply a
  durability the demo cannot provide.
* No TLS. The Ingress block has a comment saying where a cert-manager annotation goes;
  the project ships no certificate, and committing a self-signed one teaches the wrong
  habit.
* No autoscaling, no PDB per service, no network policy. Worth adding before anything
  real ran on this.

---

## 6. Observability

### Actuator

`/actuator/health` on every service, with `/actuator/health/readiness` and
`/actuator/health/liveness` for the probes. Details are shown only to authenticated
callers, so `/actuator/env` never leaks a password to an anonymous probe.

### The ELK profile

```bash
docker compose --profile elk up -d
```

Filebeat discovers the FleetFlow containers through the Docker API, forwards their
structured logs to Logstash, which indexes them by day into Elasticsearch and ships
them to Kibana at http://localhost:5601.

Create a data view for `fleetflow-logs-*`, then search by correlation id:

```
correlationId : "9f2c1a5e-4a3b-4f77-9d21-6b0e2c8a1d34"
```

That returns the gateway access log, every service that touched the request, and every
Kafka event it produced, in one place. This is the payoff for threading `X-Correlation-ID`
through the whole stack rather than logging it only at the edge.

---

## 7. Testing

```bash
# Everything
mvn -f backend/pom.xml test

# Unit tests only (no containers)
mvn -f backend/pom.xml test -DexcludedGroups=integration

# Integration tests (Testcontainers: pulls and starts real containers)
mvn -f backend/pom.xml test -Dgroups=integration

# Frontend
cd frontend && npm run lint && npm run typecheck && npm run build
```

Integration tests are tagged `@Tag("integration")` and need a working Docker. Testcontainers
1.21.4 is pinned: earlier versions cannot negotiate with Docker 29.

---

## 8. Continuous integration

`.github/workflows/ci.yml` (backend) and `.github/workflows/frontend.yml` (SPA).

**Backend:** compile the reactor → unit tests → integration tests on a runner that can
start containers → build each of the nine images → assert the image runs as a non-root
user → validate the Compose file for all three profiles.

**Frontend:** `npm ci` → lint → typecheck (`vue-tsc` in strict mode, so an unused import
fails the build) → production build → build the image → assert the built SPA is actually
served and proxies.

Neither workflow deploys anywhere. There is no cluster to deploy to, and a workflow
that claims a deployment it does not perform is worse than one that does not mention it.