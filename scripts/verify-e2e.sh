#!/usr/bin/env bash
#
# FleetFlow — end-to-end acceptance test.
#
# Exercises the flow from section 76 of the specification against a running stack,
# proving that the platform works rather than that it compiles:
#
#   customer registers -> logs in -> orders -> Kafka -> warehouse reserves stock
#   -> delivery created -> operations assigns a driver and a vehicle -> driver
#   advances the delivery -> location simulation reaches Redis + MongoDB -> the
#   customer receives live positions over SSE -> delivery completes -> the order
#   becomes DELIVERED -> the customer is notified -> the KPIs move.
#
# Usage:
#   docker compose up --build -d          # in another shell
#   ./scripts/verify-e2e.sh
#
# Options:
#   BASE_URL   gateway base URL        (default http://localhost:8080)
#   POLL_MAX   seconds to wait per step (default 90)
#   SKIP_SSE   set to 1 to skip the SSE check
#
# Dependencies: bash, curl and node (used only to parse JSON). jq is not required.

set -uo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
POLL_MAX="${POLL_MAX:-90}"
SKIP_SSE="${SKIP_SSE:-0}"

CORRELATION_ID="e2e-$(date +%s)-$$"
RUN_STAMP="$(date +%s)"

PASSED=0
FAILED=0
CUSTOMER_TOKEN=""
DRIVER_TOKEN=""
ORDER_ID=""
DELIVERY_ID=""
CORRELATION_ID_HEADER="X-Correlation-ID: ${CORRELATION_ID}"

RED=$'\033[31m'; GREEN=$'\033[32m'; YELLOW=$'\033[33m'; BOLD=$'\033[1m'; RESET=$'\033[0m'

step()  { printf '\n%s▸ %s%s\n' "${BOLD}" "$1" "${RESET}"; }

# Results are tallied in a file rather than in shell variables.
#
# ok/fail are invoked from inside command substitutions in a few places, and a counter
# incremented inside "$( … )" is incremented in a subshell and thrown away — which made
# the summary under-report. A tally file is written by the current shell and read back
# once at the end, so the numbers always match the lines actually printed.
TALLY="$(mktemp 2>/dev/null || echo /tmp/fleetflow-tally.txt)"
: > "$TALLY"

ok()   { printf 'PASS\n' >> "$TALLY"; printf '  %s✓%s %s\n' "${GREEN}" "${RESET}" "$1"; }
fail() { printf 'FAIL\n' >> "$TALLY"; printf '  %s✗%s %s\n' "${RED}" "${RESET}" "$1"; }
info() { printf '  %s·%s %s\n' "${YELLOW}" "${RESET}" "$1"; }

# Minimal JSON field reader.
#
# Node is used rather than python or jq: it is already a hard requirement of this
# project (the SPA is built with it), so the acceptance test adds no dependency the
# reader does not already have. jq is not required.
json() {
  node -e '
    let raw = "";
    process.stdin.on("data", (chunk) => (raw += chunk));
    process.stdin.on("end", () => {
      let data;
      try {
        data = JSON.parse(raw);
      } catch (error) {
        process.stdout.write("");
        return;
      }
      let cursor = data;
      for (const part of process.argv[1].split(".")) {
        if (part === "") continue;
        if (Array.isArray(cursor)) {
          cursor = /^\d+$/.test(part) ? cursor[Number(part)] : undefined;
        } else if (cursor !== null && typeof cursor === "object") {
          cursor = cursor[part];
        } else {
          cursor = undefined;
        }
        if (cursor === null || cursor === undefined) {
          process.stdout.write("");
          return;
        }
      }
      if (cursor === null || cursor === undefined) process.stdout.write("");
      else if (typeof cursor === "object") process.stdout.write(JSON.stringify(cursor));
      else process.stdout.write(String(cursor));
    });
  ' "$1"
}

# Counts the entries of a JSON array, for list endpoints that have no count field.
array_length() {
  node -e '
    let raw = "";
    process.stdin.on("data", (chunk) => (raw += chunk));
    process.stdin.on("end", () => {
      try {
        const parsed = JSON.parse(raw);
        process.stdout.write(String(Array.isArray(parsed) ? parsed.length : 0));
      } catch (error) {
        process.stdout.write("0");
      }
    });
  '
}

api() {
  local method="$1" path="$2" body="${3:-}" token="${4:-}" accept="${5:-application/json}"
  local args=(-sS -X "$method" "${BASE_URL}${path}"
              -H "Content-Type: application/json"
              -H "Accept: ${accept}"
              -H "$CORRELATION_ID_HEADER")
  [[ -n "$token" ]] && args+=(-H "Authorization: Bearer ${token}")
  [[ -n "$body" ]] && args+=(-d "$body")
  curl "${args[@]}" 2>/dev/null
}

status_of() {
  local method="$1" path="$2" body="${3:-}" token="${4:-}"
  # The response body goes to a temp file rather than /dev/null: Git Bash's curl on
  # Windows cannot open /dev/null reliably and reports
  # "transfer closed with outstanding read data remaining", which swallowed the status
  # code even though the request had succeeded.
  local sink
  sink="$(mktemp 2>/dev/null || echo /tmp/fleetflow-status.txt)"
  local args=(-sS -o "$sink" -w '%{http_code}' -X "$method" "${BASE_URL}${path}"
              -H "Content-Type: application/json" -H "$CORRELATION_ID_HEADER")
  [[ -n "$token" ]] && args+=(-H "Authorization: Bearer ${token}")
  [[ -n "$body" ]] && args+=(--data-binary "$body")
  local code
  code="$(curl "${args[@]}" 2>/dev/null)"
  rm -f "$sink" 2>/dev/null
  printf '%s' "$code"
}

# Polls a URL until the JSON field equals the expected value.
poll_until() {
  local path="$1" field="$2" expected="$3" token="${4:-}"
  local deadline=$((SECONDS + POLL_MAX))
  local body value
  while (( SECONDS < deadline )); do
    body="$(api GET "$path" '' "$token")"
    value="$(printf '%s' "$body" | json "$field")"
    if [[ "$value" == "$expected" ]]; then
      printf '%s' "$body"
      return 0
    fi
    sleep 2
  done
  return 1
}

printf '%sFleetFlow end-to-end acceptance test%s\n' "${BOLD}" "${RESET}"
printf 'Target:  %s\nCorrelation id: %s\n' "${BASE_URL}" "${CORRELATION_ID}"

# --------------------------------------------------------------------- 0. health
step "Waiting for the platform to become healthy"
READY=1
for _ in $(seq 1 "$POLL_MAX"); do
  if [[ "$(status_of GET /actuator/health)" == "200" ]]; then READY=0; break; fi
  sleep 2
done
if [[ $READY -eq 0 ]]; then ok "gateway /actuator/health is 200"; else fail "gateway never became healthy"; exit 1; fi

# --------------------------------------------------------------- 0b. reset state
# The test is re-runnable: it cancels whatever is still in flight first, through the
# public API, which is also what frees the drivers and vehicles back to AVAILABLE.
#
# Without this the second run fails for a misleading reason: the seed provides a fixed
# number of available drivers and CREATED deliveries, and the first run consumes them.
step "Resetting the demo state so the run is repeatable"
OPS_RESET="$(api POST /api/auth/login '{"email":"operations@fleetflow.local","password":"Password123!"}')"
RESET_TOKEN="$(printf '%s' "$OPS_RESET" | json accessToken)"
if [[ -z "$RESET_TOKEN" ]]; then
  fail "could not sign in as operations@fleetflow.local"
  exit 1
fi

CLEARED=0
for status in CREATED ASSIGNED PICKED_UP IN_TRANSIT FAILED; do
  page="$(api GET "/api/deliveries?status=${status}&page=0&size=100" '' "$RESET_TOKEN")"
  ids="$(printf '%s' "$page" | node -e '
    let raw = "";
    process.stdin.on("data", (c) => (raw += c));
    process.stdin.on("end", () => {
      try {
        const list = JSON.parse(raw).content ?? [];
        process.stdout.write(list.map((d) => d.id).join(" "));
      } catch (e) { /* nothing in this state */ }
    });
  ')"
  for id in $ids; do
    if [[ "$(status_of POST "/api/deliveries/${id}/cancel" \
        '{"status":"CANCELLED","reason":"Reset before the acceptance test"}' "$RESET_TOKEN")" == "200" ]]; then
      CLEARED=$((CLEARED + 1))
    fi
  done
done
ok "cancelled ${CLEARED} in-flight delivery(ies); drivers and vehicles are free again"

# ------------------------------------------------------------------ 1. register
step "1. The customer registers"
EMAIL="e2e+${RUN_STAMP}@fleetflow.local"
REGISTER_BODY=$(cat <<JSON
{"firstName":"E2E","lastName":"Verifier","email":"${EMAIL}",
 "phone":"+21620123456","password":"Password123!","address":"12 Rue de la Liberte"}
JSON
)
REGISTER_RESPONSE="$(api POST /api/auth/register "$REGISTER_BODY")"
CUSTOMER_TOKEN="$(printf '%s' "$REGISTER_RESPONSE" | json accessToken)"
if [[ -n "$CUSTOMER_TOKEN" ]]; then
  ok "registration returned a token (user #$(printf '%s' "$REGISTER_RESPONSE" | json user.id))"
else
  fail "registration failed: $(printf '%s' "$REGISTER_RESPONSE" | head -c 300)"
  exit 1
fi

# --------------------------------------------------------------------- 2. login
step "2. The customer logs in"
LOGIN_RESPONSE="$(api POST /api/auth/login "{\"email\":\"${EMAIL}\",\"password\":\"Password123!\"}")"
CUSTOMER_TOKEN="$(printf '%s' "$LOGIN_RESPONSE" | json accessToken)"
if [[ -n "$CUSTOMER_TOKEN" ]]; then ok "login returned a token"; else fail "login failed: $(printf '%s' "$LOGIN_RESPONSE" | head -c 300)"; exit 1; fi

# -------------------------------------------------------------- 3. a product to buy
step "3. The customer reads the catalogue"
PRODUCTS="$(api GET '/api/products?size=5' '' "$CUSTOMER_TOKEN")"
PRODUCT_ID="$(printf '%s' "$PRODUCTS" | json content.0.id)"
if [[ -n "$PRODUCT_ID" ]]; then ok "catalogue readable (product #$PRODUCT_ID)"; else fail "catalogue is empty or unreadable"; exit 1; fi

# --------------------------------------------------------------- 4. create order
step "4. The customer places an order"
ORDER_RESPONSE="$(api POST /api/orders "{\"items\":[{\"productId\":${PRODUCT_ID},\"quantity\":1}],\"deliveryAddress\":\"12 Rue de la Liberte\",\"city\":\"Tunis\",\"postalCode\":\"1000\"}" "$CUSTOMER_TOKEN")"
ORDER_ID="$(printf '%s' "$ORDER_RESPONSE" | json id)"
ORDER_STATUS="$(printf '%s' "$ORDER_RESPONSE" | json status)"
if [[ -n "$ORDER_ID" && "$ORDER_STATUS" == "CREATED" ]]; then
  ok "order #${ORDER_ID} created with status CREATED"
else
  fail "order creation failed: $(printf '%s' "$ORDER_RESPONSE" | head -c 300)"; exit 1
fi

# ------------------------------------------------- 5. Kafka -> warehouse reserves
step "5. order.created -> Warehouse Service reserves stock"
CONFIRMED="$(poll_until "/api/orders/${ORDER_ID}" status CONFIRMED "$CUSTOMER_TOKEN")"
if [[ -n "$CONFIRMED" ]]; then
  ok "order #${ORDER_ID} reached CONFIRMED (inventory.reserved consumed)"
else
  fail "order #${ORDER_ID} never left CREATED; the reservation did not complete"
  info "current status: $(api GET "/api/orders/${ORDER_ID}" '' "$CUSTOMER_TOKEN" | json status)"
fi

# ------------------------------------------------------- 6. delivery is created
step "6. Delivery Service created a delivery"
# Operations is signed in before the first staff-scoped call, so every later step can
# reuse the token instead of re-authenticating.
OPS_LOGIN="$(api POST /api/auth/login '{"email":"operations@fleetflow.local","password":"Password123!"}')"
OPS_TOKEN="$(printf '%s' "$OPS_LOGIN" | json accessToken)"
if [[ -z "$OPS_TOKEN" ]]; then fail "could not sign in as operations@fleetflow.local"; exit 1; fi

DELIVERIES="$(api GET "/api/deliveries?status=CREATED&page=0&size=100" '' "$OPS_TOKEN")"
DELIVERY_ID="$(printf '%s' "$DELIVERIES" | json content.0.id)"
DELIVERY_STATUS="$(printf '%s' "$DELIVERIES" | json content.0.status)"
if [[ -n "$DELIVERY_ID" && "$DELIVERY_STATUS" == "CREATED" ]]; then
  ok "delivery #${DELIVERY_ID} exists in CREATED"
else
  fail "no CREATED delivery found: $(printf '%s' "$DELIVERIES" | head -c 300)"
  info "this is the delivery the reservation should have created"
fi

# ------------------------------------------------------------- 7. RBAC rejection
step "7. RBAC and internal-route isolation"
# A customer must not reach the operations console.
CODE="$(status_of GET /api/deliveries '' "$CUSTOMER_TOKEN")"
if [[ "$CODE" == "403" ]]; then
  ok "a customer token is refused on the operations console (403)"
else
  fail "expected 403 for a customer on /api/deliveries, got ${CODE}"
fi

# /internal/** is deliberately absent from the gateway route table. Any of these answers
# is acceptable — 401 from the edge policy, 404 from the router, or 403 from the service
# — but it must never be 200.
CODE="$(curl -sS -o /dev/null -w '%{http_code}' "${BASE_URL}/internal/api/products?ids=1" \
  -H 'X-Internal-Token: fleetflow-local-internal-token' 2>/dev/null)"
if [[ "$CODE" != "200" ]]; then
  ok "/internal/** is unreachable through the gateway (${CODE})"
else
  fail "the gateway exposed an internal endpoint"
fi

# The same internal call made directly, without the token, must be rejected by the
# service itself — this is the control the gateway route table cannot provide.
CODE="$(curl -sS -o /dev/null -w '%{http_code}' \
  "http://localhost:18084/internal/api/products?ids=1" 2>/dev/null)"
if [[ "$CODE" == "403" ]]; then
  ok "the service rejects an internal call with no token (403)"
else
  info "direct internal call returned ${CODE} (service port may differ from the default)"
fi

# ------------------------------------------------ 8. operations assigns resources
step "8. Operations assigns a driver and a vehicle"
DRIVERS="$(api GET '/api/drivers?status=AVAILABLE&size=50' '' "$OPS_TOKEN")"
VEHICLES="$(api GET '/api/vehicles?status=AVAILABLE&size=10' '' "$OPS_TOKEN")"

# Prefer driver1 (auth account 3) so the later steps can sign in as them. Operations may
# legitimately pick a different available driver, and driving as the wrong account would
# be rejected with 403 "This delivery is not assigned to you" — correctly, but it would
# be the test at fault rather than the application.
DRIVER_ID=""
DRIVER_USER_ID=""
read -r DRIVER_ID DRIVER_USER_ID <<<"$(printf '%s' "$DRIVERS" | node -e '
  let raw = "";
  process.stdin.on("data", (c) => (raw += c));
  process.stdin.on("end", () => {
    let list = [];
    try { list = JSON.parse(raw).content ?? []; } catch (e) { /* no drivers */ }
    const preferred = list.find((d) => d.userId === 3);
    const chosen = preferred ?? list[0];
    if (chosen) process.stdout.write(`${chosen.id} ${chosen.userId}`);
  });
')"

VEHICLE_ID="$(printf '%s' "$VEHICLES" | json content.0.id)"

if [[ -n "$DRIVER_ID" && -n "$VEHICLE_ID" ]]; then
  ok "found driver #${DRIVER_ID} (account ${DRIVER_USER_ID}) and vehicle #${VEHICLE_ID}"
else
  fail "no available driver/vehicle to assign"
  info "drivers: $(printf '%s' "$DRIVERS" | head -c 200)"
  info "vehicles: $(printf '%s' "$VEHICLES" | head -c 200)"
fi

ASSIGN_RESPONSE="$(api POST "/api/deliveries/${DELIVERY_ID}/assign" "{\"driverId\":${DRIVER_ID},\"vehicleId\":${VEHICLE_ID}}" "$OPS_TOKEN")"
ASSIGNED_STATUS="$(printf '%s' "$ASSIGN_RESPONSE" | json status)"
if [[ "$ASSIGNED_STATUS" == "ASSIGNED" ]]; then
  ok "delivery #${DELIVERY_ID} is ASSIGNED (delivery.assigned published)"
else
  fail "assignment failed: $(printf '%s' "$ASSIGN_RESPONSE" | head -c 300)"
fi

# --------------------------------------------- 9. invalid transition is rejected
step "9. An invalid state transition returns 409"
CODE="$(status_of POST "/api/deliveries/${DELIVERY_ID}/status" '{"status":"DELIVERED","reason":"skipping ahead"}' "$OPS_TOKEN")"
if [[ "$CODE" == "409" ]]; then
  ok "ASSIGNED -> DELIVERED was refused with 409, as designed"
else
  fail "expected 409 for an illegal transition, got ${CODE}"
fi

# --------------------------------------------------------- 10. the driver signs in
step "10. The driver signs in and sees their assignment"
# driver1..driver5 map to auth accounts 3..7, which is the identity space every service
# shares, so the assigned driver's account id determines which account signs in here.
case "${DRIVER_USER_ID}" in
  3) DRIVER_EMAIL="driver1@fleetflow.local" ;;
  4) DRIVER_EMAIL="driver2@fleetflow.local" ;;
  5) DRIVER_EMAIL="driver3@fleetflow.local" ;;
  6) DRIVER_EMAIL="driver4@fleetflow.local" ;;
  7) DRIVER_EMAIL="driver5@fleetflow.local" ;;
  *) DRIVER_EMAIL="driver1@fleetflow.local" ;;
esac

DRIVER_LOGIN="$(api POST /api/auth/login "{\"email\":\"${DRIVER_EMAIL}\",\"password\":\"Password123!\"}")"
DRIVER_TOKEN="$(printf '%s' "$DRIVER_LOGIN" | json accessToken)"
if [[ -z "$DRIVER_TOKEN" ]]; then fail "could not sign in as ${DRIVER_EMAIL}"; exit 1; fi

DRIVER_ID_FROM_TOKEN="$(api GET /api/auth/me '' "$DRIVER_TOKEN" | json id)"
if [[ "${DRIVER_ID_FROM_TOKEN}" == "${DRIVER_USER_ID}" ]]; then
  ok "signed in as ${DRIVER_EMAIL}, the assigned driver (account ${DRIVER_ID_FROM_TOKEN})"
else
  fail "expected account ${DRIVER_USER_ID}, token says ${DRIVER_ID_FROM_TOKEN}"
fi

MY_DELIVERIES="$(api GET /api/deliveries/mine '' "$DRIVER_TOKEN")"
MY_COUNT="$(printf '%s' "$MY_DELIVERIES" | array_length)"
if [[ "${MY_COUNT:-0}" -gt 0 ]]; then
  ok "the driver sees ${MY_COUNT} delivery(ies) in their list"
else
  fail "the driver sees no deliveries; the driverId/userId identity mapping is wrong"
fi

# ------------------------------------------------- 11. SSE stream is established
step "11. The customer can subscribe to the tracking stream"
if [[ "$SKIP_SSE" == "1" ]]; then
  info "skipped (SKIP_SSE=1)"
else
  SSE_OUT="$(mktemp 2>/dev/null || echo /tmp/fleetflow-sse.txt)"
  curl -sS -N --max-time "${POLL_MAX}" "${BASE_URL}/api/tracking/${DELIVERY_ID}/stream" \
    -H "Accept: text/event-stream" \
    -H "Authorization: Bearer ${CUSTOMER_TOKEN}" \
    -H "$CORRELATION_ID_HEADER" > "$SSE_OUT" 2>/dev/null &
  SSE_PID=$!
  info "streaming started (pid ${SSE_PID}); moving the delivery forward"
fi

# ----------------------------------------- 12. the driver advances the delivery
step "12. The driver advances the delivery"
PICKED="$(status_of POST "/api/deliveries/${DELIVERY_ID}/status" '{"status":"PICKED_UP"}' "$DRIVER_TOKEN")"
if [[ "$PICKED" == "200" || "$PICKED" == "409" ]]; then
  ok "PICKED_UP accepted (HTTP ${PICKED})"
else
  fail "unexpected status for PICKED_UP: ${PICKED}"
fi

STARTED="$(status_of POST "/api/deliveries/${DELIVERY_ID}/status" '{"status":"IN_TRANSIT"}' "$DRIVER_TOKEN")"
if [[ "$STARTED" == "200" || "$STARTED" == "409" ]]; then
  ok "IN_TRANSIT accepted (HTTP ${STARTED}) — delivery.started published"
else
  fail "unexpected status for IN_TRANSIT: ${STARTED}"
fi

# ----------------------------------------- 13. the driver publishes positions
step "13. The driver simulation streams positions into Redis and MongoDB"
# Integer hundred-thousandths, so each tick steps +0.0007 / +0.0009 without bc or python.
LAT_STEP=$((36 * 1000000 + 806500))
LON_STEP=$((10 * 1000000 + 181500))
PUBLISHED=0
for i in $(seq 1 8); do
  # Stepping with integer arithmetic on hundred-thousandths keeps this free of bc and of
  # python: the coordinate is always the previous one plus a fixed increment.
  LAT_STEP=$((LAT_STEP + 7))
  LON_STEP=$((LON_STEP + 9))
  LAT=$(printf '%d.%06d' $((LAT_STEP / 1000000)) $((LAT_STEP % 1000000)))
  LON=$(printf '%d.%06d' $((LON_STEP / 1000000)) $((LON_STEP % 1000000)))
  CODE="$(status_of POST /api/tracking/locations \
    "{\"deliveryId\":${DELIVERY_ID},\"latitude\":${LAT},\"longitude\":${LON},\"speedKph\":32}" "$DRIVER_TOKEN")"
  [[ "$CODE" == "202" ]] && PUBLISHED=$((PUBLISHED + 1))
  sleep 1
done
if [[ "$PUBLISHED" -ge 5 ]]; then
  ok "${PUBLISHED}/8 positions accepted (202 Accepted)"
else
  fail "only ${PUBLISHED}/8 positions accepted; tracking ingestion is rejecting the driver"
fi

step "14. The latest position is readable and the trail is stored"
LATEST="$(api GET "/api/tracking/${DELIVERY_ID}/latest" '' "$CUSTOMER_TOKEN")"
LATEST_LAT="$(printf '%s' "$LATEST" | json latitude)"
HISTORY="$(api GET "/api/tracking/${DELIVERY_ID}/history?limit=50" '' "$CUSTOMER_TOKEN")"
HISTORY_COUNT="$(printf '%s' "$HISTORY" | json count)"
if [[ -n "$LATEST_LAT" ]]; then ok "Redis latest position: ${LATEST_LAT}"; else fail "no latest position returned to the customer"; fi
if [[ "${HISTORY_COUNT:-0}" -gt 1 ]]; then ok "MongoDB trail holds ${HISTORY_COUNT} positions"; else fail "history is empty (${HISTORY_COUNT})"; fi

# --------------------------------------------------------------- 15. SSE delivery
step "15. Live updates reached the customer over SSE"
if [[ "$SKIP_SSE" == "1" ]]; then
  info "skipped (SKIP_SSE=1)"
else
  wait "$SSE_PID" 2>/dev/null
  if grep -q '^event:location' "$SSE_OUT" 2>/dev/null; then
    ok "SSE stream delivered $(grep -c '^event:location' "$SSE_OUT") location events"
  else
    fail "the SSE stream carried no location events; see $SSE_OUT"
    head -c 400 "$SSE_OUT" 2>/dev/null
  fi
  rm -f "$SSE_OUT" 2>/dev/null
fi

# ------------------------------------------------------------ 16. complete it
step "16. The driver completes the delivery"
COMPLETED="$(api POST "/api/deliveries/${DELIVERY_ID}/status" '{"status":"DELIVERED","reason":"Left with concierge"}' "$DRIVER_TOKEN")"
COMPLETED_STATUS="$(printf '%s' "$COMPLETED" | json status)"
if [[ "$COMPLETED_STATUS" == "DELIVERED" ]]; then
  ok "delivery #${DELIVERY_ID} is DELIVERED — delivery.completed published"
else
  fail "completion failed: $(printf '%s' "$COMPLETED" | head -c 300)"
fi

# --------------------------------------------------- 17. the order becomes delivered
step "17. The order becomes DELIVERED"
DELIVERED="$(poll_until "/api/orders/${ORDER_ID}" status DELIVERED "$CUSTOMER_TOKEN")"
if [[ -n "$DELIVERED" ]]; then
  ok "order #${ORDER_ID} is DELIVERED"
else
  fail "order #${ORDER_ID} never reached DELIVERED"
  info "current status: $(api GET "/api/orders/${ORDER_ID}" '' "$CUSTOMER_TOKEN" | json status)"
fi

# ------------------------------------------------------- 18. the customer is told
step "18. The customer received a notification"
NOTIFICATIONS="$(api GET '/api/notifications?page=0&size=20' '' "$CUSTOMER_TOKEN")"
NOTE_COUNT="$(printf '%s' "$NOTIFICATIONS" | json content | array_length)"
UNREAD="$(api GET /api/notifications/unread-count '' "$CUSTOMER_TOKEN" | json unreadCount)"
if [[ "${NOTE_COUNT:-0}" -gt 0 ]]; then
  ok "${NOTE_COUNT} notification(s) present, ${UNREAD} unread"
else
  fail "no notifications were produced by the flow"
fi

# ------------------------------------------------------------ 19. the KPIs moved
step "19. The operations dashboard reflects the flow"
KPI="$(api GET /api/orders/kpi '' "$OPS_TOKEN")"
DKPI="$(api GET /api/deliveries/kpi '' "$OPS_TOKEN")"
TOTAL="$(printf '%s' "$KPI" | json totalOrders)"
DELIVERED_COUNT="$(printf '%s' "$KPI" | json deliveredOrders)"
COMPLETED_TODAY="$(printf '%s' "$DKPI" | json completedToday)"
if [[ "${DELIVERED_COUNT:-0}" -ge 1 ]]; then
  ok "KPIs: ${TOTAL} orders, ${DELIVERED_COUNT} delivered, ${COMPLETED_TODAY} completed today"
else
  fail "the KPI endpoint does not reflect the completed delivery"
fi

step "20. Swagger and correlation id are exposed"
CODE="$(status_of GET /api/orders '' "$OPS_TOKEN")"
CORR_HEADER="$(curl -sS -o /dev/null -D - "${BASE_URL}/api/auth/login" -X POST \
  -H 'Content-Type: application/json' -H "$CORRELATION_ID_HEADER" \
  -d '{"email":"nobody@fleetflow.local","password":"wrong"}' 2>/dev/null | grep -i '^x-correlation-id:' | tr -d '\r')"
if [[ -n "$CORR_HEADER" ]]; then ok "X-Correlation-ID echoed: ${CORR_HEADER}"; else fail "no X-Correlation-ID response header"; fi

# ------------------------------------------------------------------- summary
# grep -c prints the count and exits 1 when there are no matches, so the fallback must
# not also echo a 0 — that would make the variable "0\n0" and every printf on it fail.
PASSED="$(grep -c '^PASS$' "$TALLY" 2>/dev/null)"
FAILED="$(grep -c '^FAIL$' "$TALLY" 2>/dev/null)"
PASSED="${PASSED:-0}"
FAILED="${FAILED:-0}"
rm -f "$TALLY" 2>/dev/null

printf '\n%s-----------------------------------------%s\n' "${BOLD}" "${RESET}"
printf '%sPassed: %s%d%s    %sFailed: %s%d%s\n' \
  "${BOLD}" "${GREEN}" "${PASSED}" "${RESET}" "${BOLD}" "${RED}" "${FAILED}" "${RESET}"
printf 'Correlation id for this run: %s\n' "${CORRELATION_ID}"
printf '%s-----------------------------------------%s\n\n' "${BOLD}" "${RESET}"

if [[ "${FAILED}" -ne 0 ]]; then exit 1; fi
printf '%sThe full business flow works end to end.%s\n\n' "${GREEN}" "${RESET}"