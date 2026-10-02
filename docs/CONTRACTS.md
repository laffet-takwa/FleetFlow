# FleetFlow — Internal Implementation Contract

**This file is the source of truth for every FleetFlow service implementation.** Read it before writing code.
Anything already implemented in `backend/common` must be *used*, never re-implemented.

Repository root: `C:\Users\takwa\FleetFlow`
Backend aggregator: `backend/pom.xml` (groupId `com.fleetflow`, parent `spring-boot-starter-parent:3.3.5`, Java 17)
Build command: `mvn -f backend/pom.xml -pl <module> -am install -DskipTests`

---

## 1. What already exists — use it, do not duplicate

Everything below lives in `backend/common/src/main/java/com/fleetflow/common/` and is already compiled.
It is picked up automatically through `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`.

### 1.1 Error handling

| Class | Purpose |
|---|---|
| `com.fleetflow.common.exception.ErrorCode` | enum of (code, HttpStatus) |
| `com.fleetflow.common.exception.BusinessException` | `throw new BusinessException(ErrorCode.CONFLICT, "...")` |
| `com.fleetflow.common.exception.ResourceNotFoundException` | `throw ResourceNotFoundException.of("Order", id)` |
| `com.fleetflow.common.exception.InvalidStateTransitionException` | `new InvalidStateTransitionException("Delivery", id, from, to)` → **409** |
| `com.fleetflow.common.api.ApiErrorResponse` | `{timestamp,status,error,message,path,correlationId,violations[]}` |
| `com.fleetflow.common.api.GlobalExceptionHandler` | already registered — **do not write your own `@RestControllerAdvice`** |
| `com.fleetflow.common.api.PageResponse<T>` | `PageResponse.from(page)` / `PageResponse.from(page, mappedList)` |

Throw `BusinessException` / `ResourceNotFoundException` / `InvalidStateTransitionException` from services.
Never return raw exceptions, never leak stack traces.

### 1.2 Correlation ID

| Class | Purpose |
|---|---|
| `com.fleetflow.common.correlation.CorrelationId` | `get()`, `getOrCreate()`, `set()`, `clear()`, `HEADER = "X-Correlation-ID"` |
| `com.fleetflow.common.correlation.CorrelationIdFilter` | already registered, runs first, echoes the header |

Any code you write that publishes an event, calls another service, or logs a business
decision should include the correlation id so the ELK story works:

```java
log.info("Reserved inventory for order {} [correlationId={}]", orderId, CorrelationId.getOrCreate());
```

When you consume a Kafka event, restore its correlation id for the duration of the handler:

```java
try (CorrelationIdScope ignored = CorrelationIdScope.open(envelope.correlationId())) { ... }
```

Use `com.fleetflow.common.correlation.CorrelationIdScope` (an `AutoCloseable` helper you may create once, in `common`).
If it does not exist yet, create it in `common` and rebuild it — do not duplicate it per service.

### 1.3 Security / JWT

| Class | Purpose |
|---|---|
| `com.fleetflow.common.security.FleetRole` | `ADMIN, OPERATIONS, DRIVER, CUSTOMER`; `.authority()` → `ROLE_ADMIN`; `.from(String)` |
| `com.fleetflow.common.security.JwtPrincipal` | record `(Long userId, String email, FleetRole role)` |
| `com.fleetflow.common.security.JwtService` | `generateToken(userId, email, role)`, `parse(token)` → `Optional<Claims>` |
| `com.fleetflow.common.security.JwtAuthenticationFilter` | already a bean; **add it to your SecurityFilterChain** |
| `com.fleetflow.common.security.SecurityUtils` | `requirePrincipal()`, `currentUserId()`, `currentRole()`, `hasRole(..)`, `isStaff()`, `requireSelfOrStaff(ownerId)` |
| `com.fleetflow.common.security.InternalServiceAuthFilter` | already registered, guards `/internal/**` |

`SecurityUtils.requireUserId()` returns the JWT subject. **That subject is the `userId` used by every other service.**
Order `customerId`, driver `userId`, notification `userId` are all the same identity space.

### 1.4 Kafka

| Class | Purpose |
|---|---|
| `com.fleetflow.common.event.EventEnvelope<T>` | `record(eventId, eventType, topic, correlationId, timestamp, payload)`; factory `EventEnvelope.of(type, topic, payload)` |
| `com.fleetflow.common.event.KafkaTopics` | topic name constants + `all()` |
| `com.fleetflow.common.event.EventTypes` | `ORDER_CREATED`, `INVENTORY_RESERVED`, ... |
| `com.fleetflow.common.event.DomainEventPublisher` | `publish(topic, key, envelope)` — bean is ready to inject |
| `com.fleetflow.common.event.KafkaDomainEventPublisher` | serialises the envelope to JSON with `KafkaTemplate<String,String>` |
| `com.fleetflow.common.idempotency.IdempotencyService` | `executeOnce(eventId, eventType, Runnable)` — bean is ready to inject |

**Kafka values on the wire are JSON strings.** A listener receives a `String`:

```java
@KafkaListener(topics = "${fleetflow.kafka.topics.order-created}", groupId = "${spring.kafka.consumer.group-id}")
public void onOrderCreated(String json) {
    EventEnvelope<OrderCreatedPayload> envelope =
        objectMapper.readValue(json, new TypeReference<EventEnvelope<OrderCreatedPayload>>() {});
    ...
}
```

Declare checked-exception free handlers by wrapping in try/catch and rethrowing a `RuntimeException`,
or accept `throws Exception` on the method (Spring Kafka permits it).

**Every consumer must be idempotent.** Wrap the handler:

```java
idempotencyService.executeOnce(envelope.eventId(), envelope.eventType(), () -> handle(envelope));
```

### 1.5 Event payloads (already defined — do not redefine)

`com.fleetflow.common.event.payload.*`:

* `OrderCreatedPayload(Long orderId, Long customerId, String city, String postalCode, int itemCount, BigDecimal totalAmount)`
* `InventoryReservedPayload(Long orderId, Long customerId, Long warehouseId, String warehouseName, BigDecimal reservedValue, int itemCount)`
* `InventoryInsufficientPayload(Long orderId, Long customerId, List<Shortfall> shortfalls)`
  with nested `Shortfall(Long productId, String productName, int requested, int available)`
* `DeliveryAssignedPayload(Long deliveryId, Long orderId, Long customerId, Long driverId, String driverName, Long vehicleId, String vehicleRegistration)`
* `DeliveryStatusChangedPayload(Long deliveryId, Long orderId, Long customerId, Long driverId, String previousStatus, String newStatus, String reason)`
* `DeliveryCompletedPayload(Long deliveryId, Long orderId, Long customerId, Long driverId, Instant completedAt, String proofOfDelivery)`

### 1.6 Persistence helper

`com.fleetflow.common.persistence.TimestampedEntity` — mapped superclass with `createdAt` / `updatedAt`
(`@PrePersist` / `@PreUpdate`). Extend it for any entity that owns both timestamps.

Flyway additionally applies `classpath:com/fleetflow/common/db/migration` (V900, `processed_event` table)
because `spring.flyway.locations` already lists it.

---

## 2. Conventions you must follow

1. **Base package** `com.fleetflow.<service>` where `<service>` is `auth`, `customer`, `order`,
   `warehouse`, `delivery`, `tracking`, `notification`. Application class
   `com.fleetflow.<service>.<Service>Application` annotated `@SpringBootApplication`.
2. **Layering** `controller/ service/ repository/ entity/ dto/ mapper/ exception/ config/ security/ kafka/ client/ seed/`
   (only create the packages you actually need).
   *Controllers must contain no business logic.* They bind, delegate, and map to HTTP.
3. **DTOs are Java `record`s.** Entities are plain classes with getters/setters. Never expose an entity in a response.
4. **Money is `BigDecimal`** with `@Column(nullable = false, precision = 12, scale = 3)` — the TND
   dinar uses 3 decimal places. Currency is a `String` column defaulting to `TND`.
5. **Enums** are stored as `VARCHAR` via `@Enumerated(EnumType.STRING)`. Never persist ordinals.
6. **Swagger**: annotate controllers with `@Tag`, endpoints with `@Operation(summary = "...", description = "...")`,
   parameters with `@Parameter`, and document non-obvious responses with `@ApiResponse`. Schemas come from records.
7. **Logging**: SLF4J only. Include the correlation id in business log lines. Never log passwords or tokens.
8. **Configuration is environment driven.** The `application.yml` for your service already exists — extend it,
   do not rewrite it. Never hard-code a hostname: use the existing `${...}` placeholders.
9. **Tests**: JUnit 5 + Mockito for unit tests. No test may require Docker unless it is explicitly
   marked as a Testcontainers integration test (`@Tag("integration")`).

---

## 3. Security configuration (identical shape in every servlet service)

`config/SecurityConfig.java`:

```java
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http, JwtAuthenticationFilter jwtFilter) throws Exception {
        http
            .csrf(AbstractHttpConfigurer::disable)
            .cors(Customizer.withDefaults())
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/actuator/health/**", "/v3/api-docs/**", "/swagger-ui/**",
                                 "/swagger-ui.html", "/api/auth/register", "/api/auth/login").permitAll()
                .anyRequest().authenticated())
            .exceptionHandling(e -> e
                .authenticationEntryPoint((request, response, ex) -> { /* 401 + ApiErrorResponse JSON */ })
                .accessDeniedHandler((request, response, ex) -> { /* 403 + ApiErrorResponse JSON */ }))
            .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}
```

The entry point / access denied handlers must emit the same `ApiErrorResponse` JSON shape as
`GlobalExceptionHandler` (timestamp, status, error, message, path, correlationId) — Spring Security
rejections happen before the advice runs.

Authorise per-endpoint with `@PreAuthorize("hasAnyRole('ADMIN','OPERATIONS')")` or
`@PreAuthorize("hasRole('CUSTOMER')")`, and add `@EnableMethodSecurity` on the configuration class.
Use `SecurityUtils.requireSelfOrStaff(ownerId)` for "own record or staff" rules — prefer it over
hand-written comparisons.

---

## 4. Service-to-service HTTP calls

Cross-service reads happen over REST with the shared internal token, because the data is needed
*synchronously* (a price at checkout, a phone number before a delivery starts).

* Caller: inject `RestClient.Builder`, set base URL from `fleetflow.services.<x>.base-url`,
  and add an interceptor sending `X-Internal-Token: ${fleetflow.internal.token}` and
  `X-Correlation-ID: ${CorrelationId.getOrCreate()}` on every request.
* Callee: expose under `/internal/api/...`, declare it `permitAll()` in Spring Security
  (the `InternalServiceAuthFilter` already rejects calls without the correct token).

If a caller cannot reach a downstream service, throw
`new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "<service> is unreachable")`
— never a 500 with a stack trace.

---

## 5. Identity and seeded IDs

`auth-service` seeds users in this exact order, with explicit ids, so every other service can
reference them deterministically:

| id | email | role |
|---|---|---|
| 1 | admin@fleetflow.local | ADMIN |
| 2 | operations@fleetflow.local | OPERATIONS |
| 3–7 | driver1..driver5@fleetflow.local | DRIVER |
| 8–17 | customer1..customer10@fleetflow.local | CUSTOMER |

Password for all demo accounts: `Password123!`

`auth-service` must insert with explicit ids and then reset the sequence
(`SELECT setval('users_id_seq', (SELECT MAX(id) FROM users))`) so auto-generated ids continue from 18.

---

## 6. Business rules (enforced server-side, tested)

### Order
* A customer may only create an order for **active** products, with `quantity > 0`.
* `subtotal = Σ(quantity × unitPrice)`; `deliveryFee` from config (`fleetflow.pricing.delivery-fee`);
  `totalAmount = subtotal + deliveryFee`. All three are computed by the server, never trusted from the client.
* The order is stored with status `CREATED` and `order.created` is published.
* A customer may cancel only while the order is `CREATED` or `CONFIRMED` ("before delivery processing begins").
  Staff may cancel while `CREATED`, `CONFIRMED` or `PROCESSING`.

### Order status machine
```
CREATED            -> CONFIRMED, CANCELLED
CONFIRMED          -> PROCESSING, CANCELLED
PROCESSING         -> READY_FOR_DELIVERY, CANCELLED
READY_FOR_DELIVERY -> OUT_FOR_DELIVERY
OUT_FOR_DELIVERY   -> DELIVERED
DELIVERED          -> (terminal)
CANCELLED          -> (terminal)
```
Invalid transitions → `InvalidStateTransitionException` (**409**).
Every transition appends a row to `order_status_history` so the UI can render the timeline.

### Inventory
* Reserve only when `availableQuantity >= requested`.
* `availableQuantity -= qty`, `reservedQuantity += qty`. Neither may go negative.
* On failure publish `inventory.insufficient` and **do not** publish `inventory.reserved`.
* Reserving again for an order that already holds a reservation must be a no-op (use `inventory_reservation`
  with a unique key on `(order_id, product_id)`).

### Driver
* One active delivery per driver in this MVP.
* A driver must be `AVAILABLE` before assignment.
* A driver may only read/modify deliveries where `driver.userId == JWT subject`.

### Vehicle
* Must be `AVAILABLE` before assignment.
* Cannot be attached to more than one non-terminal delivery.

### Delivery status machine
```
CREATED   -> ASSIGNED, CANCELLED
ASSIGNED  -> PICKED_UP, CANCELLED
PICKED_UP -> IN_TRANSIT, FAILED
IN_TRANSIT-> DELIVERED, FAILED
DELIVERED -> (terminal)
FAILED    -> (terminal, staff may requeue to ASSIGNED via an explicit endpoint)
CANCELLED -> (terminal)
```
`DELIVERED -> IN_TRANSIT` must return **409**.

---

## 7. Delivery rules of thumb

* `delivery.assigned` — after operations assigns a driver **and** vehicle.
* `delivery.picked-up` — `ASSIGNED -> PICKED_UP`, body `DeliveryStatusChangedPayload`.
* `delivery.started` — `PICKED_UP -> IN_TRANSIT`, body `DeliveryStatusChangedPayload`.
* `delivery.completed` — `IN_TRANSIT -> DELIVERED`, body `DeliveryCompletedPayload`; also frees the
  driver (`AVAILABLE`) and the vehicle (`AVAILABLE`).
* `delivery.failed` / `delivery.cancelled` — body `DeliveryStatusChangedPayload` with `reason`.

Topic names come from `fleetflow.kafka.topics.*` in your `application.yml` — never hard-code them.

---

## 8. Definition of done for a service

* [ ] `mvn -f backend/pom.xml -pl <module> -am install -DskipTests` succeeds with **zero** warnings that matter.
* [ ] `mvn -f backend/pom.xml -pl <module> -am test` passes.
* [ ] Flyway migrations exist in `src/main/resources/db/migration` and match the entities
      (`spring.jpa.hibernate.ddl-auto=validate` will fail at boot otherwise). Write the DDL first, then the entity.
* [ ] Seed data runner is idempotent (safe on every boot) and gated by `fleetflow.seed.enabled`.
* [ ] Swagger UI reachable at `/swagger-ui.html`.
* [ ] Actuator `/actuator/health` reports UP.
* [ ] Unit tests cover the happy path **and** the rejection cases listed in section 6.
* [ ] No hard-coded URLs, no secrets in code, no `System.out`, no `printStackTrace`.
* [ ] No comments that merely restate the code.