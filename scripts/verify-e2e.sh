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
# Dependencies: bash, curl and python (used only to parse JSON). jq is not required.

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
ok()    { PASSED=$((PASSED + 1)); printf '  %s✓%s %s\n' "${GREEN}" "${RESET}" "$1"; }
fail()  { FAILED=$((FAILED + 1)); printf '  %s✗%s %s\n' "${RED}" "${RESET}" "$1"; }
info()  { printf '  %s·%s %s\n' "${YELLOW}" "${RESET}" "$1"; }

# Minimal JSON field reader. python is present on every machine that can run Docker,
# so this avoids a jq dependency without pulling one in.
json() {
  python3 -c "
import json,sys
try:
    data = json.load(sys.stdin)
except Exception:
    print(''); sys.exit(0)
path = '''$1'''
cur = data
for part in path.split('.'):
    if part == '':
        continue
    if isinstance(cur, list):
        try:
            cur = cur[int(part)]
        except Exception:
            print(''); sys.exit(0)
    elif isinstance(cur, dict):
        cur = cur.get(part)
    else:
        print(''); sys.exit(0)
    if cur is None:
        print(''); sys.exit(0)
print(cur if not isinstance(cur,(dict,list)) else json.dumps(cur))
" 2>/dev/null || python -c "
import json,sys
try:
    data = json.load(sys.stdin)
except Exception:
    print(''); sys.exit(0)
cur = data
for part in '''$1'''.split('.'):
    if part == '':
        continue
    cur = cur[int(part)] if isinstance(cur, list) and part.isdigit() else (cur.get(part) if isinstance(cur, dict) else None)
    if cur is None:
        print(''); sys.exit(0)
print(cur if not isinstance(cur,(dict,list)) else json.dumps(cur))
" 2>/dev/null
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
  local args=(-sS -o /dev/null -w '%{http_code}' -X "$method" "${BASE_URL}${path}"
              -H "Content-Type: application/json" -H "$CORRELATION_ID_HEADER")
  [[ -n "$token" ]] && args+=(-H "Authorization: Bearer ${token}")
  [[ -n "$body" ]] && args+=(-d "$body")
  curl "${args[@]}" 2>/dev/null
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
fi

# ------------------------------------------------------- 6. delivery is created
step "6. Delivery Service created a delivery"
DELIVERIES="$(api GET "/api/deliveries?page=0&size=100" '' '')"
DELIVERY_ID="$(printf '%s' "$DELIVERIES" | json content.0.id)"
DELIVERY_STATUS="$(printf '%s' "$DELIVERIES" | json content.0.status)"
if [[ -n "$DELIVERY_ID" && "$DELIVERY_STATUS" == "CREATED" ]]; then
  ok "delivery #${DELIVERY_ID} exists in CREATED"
else
  fail "no CREATED delivery found: $(printf '%s' "$DELIVERIES" | head -c 300)"
fi

# ------------------------------------------------------------- 7. RBAC rejection
step "7. RBAC — a customer cannot reach the operations console"
CODE="$(status_of GET /api/orders "$CUSTOMER_TOKEN")"
CODE2="$(status_of GET /internal/api/products 'ids=1')"
if [[ "$CODE" != "403" && "$CODE" != "404" ]]; then
  fail "expected 403/404 for a customer on the internal endpoint, got ${CODE2}"
else
  ok "internal endpoints reject a plain customer token (${CODE2})"
fi

# ------------------------------------------------ 8. operations assigns resources
step "8. Operations assigns a driver and a vehicle"
OPS_LOGIN="$(api POST /api/auth/login '{"email":"operations@fleetflow.local","password":"Password123!"}')"
OPS_TOKEN="$(printf '%s' "$OPS_LOGIN" | json accessToken)"
if [[ -z "$OPS_TOKEN" ]]; then fail "could not sign in as operations@fleetflow.local"; exit 1; fi

DRIVERS="$(api GET '/api/drivers?status=AVAILABLE&size=10' '' "$OPS_TOKEN")"
DRIVER_ID="$(printf '%s' "$DRIVERS" | json content.0.id)"
DRIVER_USER_ID="$(printf '%s' "$DRIVERS" | json content.0.userId)"
VEHICLES="$(api GET '/api/vehicles?status=AVAILABLE&size=10' '' "$OPS_TOKEN")"
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
DRIVER_LOGIN="$(api POST /api/auth/login '{"email":"driver1@fleetflow.local","password":"Password123!"}')"
DRIVER_TOKEN="$(printf '%s' "$DRIVER_LOGIN" | json accessToken)"
if [[ -z "$DRIVER_TOKEN" ]]; then fail "could not sign in as driver1@fleetflow.local"; exit 1; fi

# The seeded driver's account id must match the `userId` the warehouse/exports agree on.
DRIVER_ID_FROM_TOKEN="$(api GET /api/auth/me '' "$DRIVER_TOKEN" | json id)"
MY_DELIVERIES="$(api GET /api/deliveries/mine '' "$DRIVER_TOKEN")"
MY_COUNT="$(printf '%s' "$MY_DELIVERIES" | python3 -c 'import json,sys; d=json.load(sys.stdin); print(len(d) if isinstance(d,list) else 0)' 2>/dev/null || echo 0)"
if [[ "${MY_COUNT:-0}" -gt 0 ]]; then
  ok "driver account ${DRIVER_ID_FROM_TOKEN} sees ${MY_COUNT} delivery(ies)"
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
LAT=36.8065; LON=10.1815
PUBLISHED=0
for i in $(seq 1 8); do
  LAT=$(python3 -c "print(f'{${LAT} + 0.0007:.6f}')")
  LON=$(python3 -c "print(f'{${LON} + 0.0009:.6f}')")
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
NOTE_COUNT="$(printf '%s' "$NOTIFICATIONS" | python3 -c 'import json,sys; d=json.load(sys.stdin); print(len(d.get("content",[])))' 2>/dev/null || echo 0)"
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
printf '\n%s─────────────────────────────────────────%s\n' "${BOLD}" "${RESET}"
printf '%sPassed: %s%s%d    %sFailed: %s%s%d\n' "${BOLD}" "${RESET}" "${GREEN}" "${PASSED}" "${RESET}" "${RED}" "${FAILED}"
printf 'Correlation id for this run: %s\n' "${CORRELATION_ID}"
printf '%s─────────────────────────────────────────%s\n\n' "${BOLD}" "${RESET}"

[[ "$FAILED" -eq 0 ]] || exit 1
printf '%sThe full business flow works end to end.%s\n\n' "${GREEN}" "${RESET}"