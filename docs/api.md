# FleetFlow API Reference

Every endpoint is reachable through the gateway at `http://localhost:8080`.
Swagger UI is served by each service (`/swagger-ui.html`) and lists its own endpoints
with schemas, parameters and examples generated from the code — that is the
authoritative reference. This document is the map: what exists, who may call it, and
which HTTP status means what.

## Conventions

**Authentication.** Every request except the two public auth endpoints needs
`Authorization: Bearer <accessToken>`. The gateway verifies the token before routing,
so a bad token never reaches a business service.

**Correlation id.** Send `X-Correlation-ID` and it is honoured, echoed on the response,
propagated to every downstream service and stamped onto every Kafka event the request
produces. Omit it and the gateway generates one. Look for it in the response headers.

**Errors.** Uniform across all eight services *and* the gateway:

```json
{
  "timestamp": "2026-10-02T13:53:11.204Z",
  "status": 409,
  "error": "INVALID_STATE_TRANSITION",
  "message": "Delivery 845 cannot move from DELIVERED to IN_TRANSIT",
  "path": "/api/deliveries/845/status",
  "correlationId": "9f2c1a5e-4a3b-4f77-9d21-6b0e2c8a1d34",
  "violations": [ { "field": "items[0].quantity", "message": "must be greater than 0" } ]
}
```

`violations` appears only for validation failures. Stack traces never appear.

**Statuses used.** `200` read/update · `201` created · `202` accepted for processing ·
`204` no content · `400` malformed · `401` missing/invalid token · `403` authenticated
but not permitted · `404` unknown resource · `409` illegal state or conflict ·
`422` well-formed but not satisfiable · `500` unexpected.

**Pagination.** List endpoints accept `page` (0-based) and `size`, and return:

```json
{ "content": [], "page": 0, "size": 20, "totalElements": 143, "totalPages": 8, "first": true, "last": false }
```

---

## Auth — `:8081`

Public. All others authenticated.

| Method | Path | Who | Purpose |
|---|---|---|---|
| `POST` | `/api/auth/register` | public | Create a CUSTOMER account. 201 returns a token and the user. |
| `POST` | `/api/auth/login` | public | Exchange credentials for a token. |
| `GET` | `/api/auth/me` | any | The caller's own record. |
| `GET` | `/api/auth/users?role=&search=&page=&size=` | ADMIN, OPERATIONS | Search accounts. |

Registration validates name length, email format and uniqueness, phone format
`^\+?[0-9]{6,20}$`, and a password of 8–72 characters containing a letter and a digit.
The role is always `CUSTOMER`; a role in the request body is ignored. A duplicate email
is `409`.

Login never reveals whether an email exists: both an unknown email and a wrong password
return the same `401 INVALID_CREDENTIALS`, and the password check always runs so the
timing does not leak it either.

## Customer — `:8082`

| Method | Path | Who | Purpose |
|---|---|---|---|
| `GET` | `/api/customers/me` | any | Own profile, lazily created on first access. |
| `PUT` | `/api/customers/me` | any | Update own profile. |
| `GET` | `/api/customers?search=&page=&size=` | staff | Search customers. |
| `GET` | `/api/customers/{id}` | owner, staff | One customer. |
| `GET` | `/internal/api/customers/by-user/{userId}/contact` | internal token | Contact snapshot for delivery-service. |
| `POST` | `/internal/api/customers` | internal token | Pre-create a profile at registration. |

`/internal/**` is absent from the gateway route table. It is guarded by a shared
`X-Internal-Token` header, so it is unreachable from outside the platform network.

## Warehouse — `:8084`

Reads are open to any authenticated user (a customer browses the catalogue); writes are
ADMIN/OPERATIONS.

| Method | Path | Who | Purpose |
|---|---|---|---|
| `GET` | `/api/products?search=&category=&active=&page=&size=` | any | Browse the catalogue. |
| `GET` | `/api/products/categories` | any | Distinct categories. |
| `GET` | `/api/products/{id}` | any | One product. |
| `POST` / `PUT` | `/api/products`, `/api/products/{id}` | staff | Create and update. |
| `GET` | `/api/warehouses` | any | List warehouses. |
| `POST` / `PUT` | `/api/warehouses`, `/api/warehouses/{id}` | staff | Manage warehouses. |
| `GET` | `/api/inventory?warehouseId=&search=&stockStatus=&category=&page=&size=` | staff | Stock levels. |
| `PUT` | `/api/inventory/{id}` | staff | Adjust by a signed delta. |
| `GET` | `/api/inventory/low-stock` | staff | Everything at or below the threshold. |
| `GET` | `/api/inventory/summary` | staff | Counts by stock status. |
| `GET` | `/internal/api/products?ids=1,2,3` | internal token | Product snapshots for checkout. |

Stock status is derived, never stored: `OUT_OF_STOCK` at zero, `LOW_STOCK` at or below
the threshold, `IN_STOCK` otherwise. An adjustment that would take availability below
zero is refused with `409`.

## Order — `:8083`

| Method | Path | Who | Purpose |
|---|---|---|---|
| `POST` | `/api/orders` | CUSTOMER | Place an order. 201, publishes `order.created`. |
| `GET` | `/api/orders?status=&search=&from=&to=&sort=&page=&size=` | owner (own only), staff (all) | List orders. |
| `GET` | `/api/orders/{id}` | owner, staff | One order with items and timeline. |
| `POST` | `/api/orders/{id}/cancel` | owner, staff | Cancel, with a reason. |
| `PATCH` | `/api/orders/{id}/status` | staff | Manual advance, with a note. |
| `GET` | `/api/orders/kpi` | staff | Dashboard counters. |
| `GET` | `/api/orders/analytics/daily?days=14` | staff | Per-day series, gaps filled with zero. |
| `GET` | `/internal/api/orders/{id}/summary` | internal token | For notification-service. |
| `GET` | `/internal/api/orders/{id}/items` | internal token | Order lines for the reservation. |

`subtotal`, `deliveryFee` and `totalAmount` are computed server-side from the catalogue
price at checkout. The client cannot influence them.

Status machine — an illegal move is `409`:

```
CREATED            -> CONFIRMED, CANCELLED
CONFIRMED          -> PROCESSING, CANCELLED
PROCESSING         -> READY_FOR_DELIVERY, CANCELLED
READY_FOR_DELIVERY -> OUT_FOR_DELIVERY
OUT_FOR_DELIVERY   -> DELIVERED
DELIVERED, CANCELLED are terminal
```

A customer may cancel only from `CREATED` or `CONFIRMED` — before delivery processing
begins. Staff may also cancel from `PROCESSING`.

## Delivery — `:8085`

| Method | Path | Who | Purpose |
|---|---|---|---|
| `GET` | `/api/deliveries?status=&driverId=&vehicleId=&from=&to=&page=&size=` | staff | List deliveries. |
| `GET` | `/api/deliveries/active` | staff | Non-terminal deliveries. |
| `GET` | `/api/deliveries/{id}` | staff, assigned driver, owner | One delivery. |
| `GET` | `/api/deliveries/mine` | DRIVER | The caller's own deliveries. |
| `GET` | `/api/deliveries/{id}/customer` | assigned driver, staff | Customer contact for the stop. |
| `POST` | `/api/deliveries/{id}/assign` | staff | Allocate driver and vehicle together. |
| `POST` | `/api/deliveries/{id}/status` | assigned driver, staff | Advance the lifecycle. |
| `POST` | `/api/deliveries/{id}/cancel` | staff | Cancel with a reason. |
| `POST` | `/api/deliveries/{id}/requeue` | staff | `FAILED` back to `ASSIGNED`. |
| `GET` | `/api/deliveries/kpi` | staff | Dashboard counters. |
| `GET` | `/api/drivers?status=&search=&page=&size=` | staff | Browse drivers. |
| `GET` | `/api/drivers/me` | DRIVER | The caller's own driver record. |
| `PUT` | `/api/drivers/me/status` | DRIVER | Set `AVAILABLE` or `OFFLINE`. |
| `GET` | `/api/drivers/{id}` | staff, self | One driver. |
| `GET` | `/api/vehicles?status=&type=&search=&page=&size=` | staff | Browse vehicles. |
| `POST` / `PUT` | `/api/vehicles`, `/api/vehicles/{id}` | staff | Manage vehicles. |

Status machine — an illegal move is `409`:

```
CREATED    -> ASSIGNED, CANCELLED
ASSIGNED   -> PICKED_UP, CANCELLED
PICKED_UP  -> IN_TRANSIT, FAILED
IN_TRANSIT -> DELIVERED, FAILED
FAILED     -> ASSIGNED   (staff requeue only)
DELIVERED, CANCELLED are terminal
```

`DELIVERED -> IN_TRANSIT` is refused with `409`. Assignment requires the driver to be
`AVAILABLE` with no other open delivery, and the vehicle `AVAILABLE` and unattached;
otherwise `409`. A driver acting on someone else's delivery is `403`.

A driver may only set themselves `AVAILABLE` or `OFFLINE` — `ON_DELIVERY` is a
consequence of accepting an assignment, not something a driver can assert.

## Tracking — `:8086`

| Method | Path | Who | Purpose |
|---|---|---|---|
| `POST` | `/api/tracking/locations` | assigned driver, staff | One position fix. **202 Accepted.** |
| `GET` | `/api/tracking/{deliveryId}/latest` | staff, driver, owner | Newest position (Redis). |
| `GET` | `/api/tracking/{deliveryId}/history?limit=200` | staff, driver, owner | Trail (MongoDB). |
| `GET` | `/api/tracking/{deliveryId}/status` | staff, driver, owner | Tracking state and freshness. |
| `GET` | `/api/tracking/{deliveryId}/stream` | staff, driver, owner | **SSE** live positions. |
| `GET` | `/api/tracking/active` | staff | Every active delivery with its latest position. |

Request body:

```json
{ "deliveryId": 845, "latitude": 36.8065, "longitude": 10.1815,
  "speedKph": 32.5, "heading": 275.0, "recordedAt": "2026-10-02T14:05:00Z" }
```

Latitude outside ±90, longitude outside ±180, a position older than the current one, or
a delivery that is not active are all rejected — `400` for coordinates, `409` for the
rest. The SSE stream emits `event:location` frames with a `LocationResponse` payload,
sends an immediate `event:connected` frame on subscribe, and completes when the
delivery reaches a terminal state.

The stream is read by the SPA with `fetch` rather than `EventSource`, so the access
token stays in the `Authorization` header instead of the query string, where it would
be written to every proxy log on the way through.

## Notification — `:8087`

Every endpoint is scoped to the caller; another user's notification is `403`.

| Method | Path | Purpose |
|---|---|---|
| `GET` | `/api/notifications?unreadOnly=&page=&size=` | List, newest first. |
| `GET` | `/api/notifications/unread-count` | Unread total. |
| `POST` | `/api/notifications/{id}/read` | Mark one read. 204. |
| `POST` | `/api/notifications/read-all` | Mark all read. 204. |
| `DELETE` | `/api/notifications/{id}` | Remove one. 204. |
| `GET` | `/api/notifications/stream` | **SSE** push of new notifications and unread counts. |

Notifications are produced by consuming events, never by the request path. Each handler
is idempotent on `eventId`, so a redelivered event cannot produce a duplicate
notification.

## Gateway — `:8080`

Public without a token: `POST /api/auth/register`, `POST /api/auth/login`,
`/actuator/**`, `/v3/api-docs/**`, `/swagger-ui/**`.

Routes (service discovery by URL, not by Eureka):

| Path prefix | Service |
|---|---|
| `/api/auth/**` | auth |
| `/api/customers/**` | customer |
| `/api/orders/**` | order |
| `/api/products/**`, `/api/inventory/**`, `/api/warehouses/**` | warehouse |
| `/api/drivers/**`, `/api/vehicles/**`, `/api/deliveries/**` | delivery |
| `/api/tracking/**` | tracking |
| `/api/notifications/**` | notification |

`/internal/**` is deliberately **not** routed. Those endpoints are only reachable from
inside the platform network.

---

## Worked example: place an order

```bash
TOKEN=$(curl -sS -X POST http://localhost:8080/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"email":"customer1@fleetflow.local","password":"Password123!"}' \
  | python -c 'import json,sys; print(json.load(sys.stdin)["accessToken"])')

curl -sS -X POST http://localhost:8080/api/orders \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -H 'X-Correlation-ID: my-trace-1' \
  -d '{"items":[{"productId":1,"quantity":2}],
       "deliveryAddress":"12 Rue Habib Bourguiba","city":"Tunis","postalCode":"1000"}'
```

Then watch it move:

```bash
curl -sS -H "Authorization: Bearer $TOKEN" \
  http://localhost:8080/api/orders/1 | python -m json.tool
```

The same correlation id now appears in the gateway log, in the order, warehouse,
delivery and notification logs, and on the `order.created`, `inventory.reserved`,
`delivery.assigned`, `delivery.started` and `delivery.completed` records in Kafka.

`scripts/verify-e2e.sh` automates exactly this walk-through against a running stack.