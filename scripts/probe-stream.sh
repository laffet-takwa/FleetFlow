#!/usr/bin/env bash
# Compares how many bytes actually arrive from a service directly versus through the
# gateway, for the same authenticated request. A short read means the connection is
# being closed before the response is complete.
set -u
API=http://localhost:18080
OPS_PORT=http://localhost:18082

TOKEN=$(curl -sS -X POST "$API/api/auth/login" -H 'Content-Type: application/json' \
  -d '{"email":"operations@fleetflow.local","password":"Password123!"}' \
  | node -e 'let r="";process.stdin.on("data",c=>r+=c);process.stdin.on("end",()=>console.log(JSON.parse(r).accessToken))')

check() {
  local label="$1" url="$2"
  local out code
  out=$(mktemp); err=$(mktemp)
  code=$(curl -sS -o "$out" -w '%{http_code}' "$url" -H "Authorization: Bearer $TOKEN" 2>"$err")
  local bytes declared
  bytes=$(wc -c < "$out" | tr -d ' ')
  declared=$(curl -sS -o /dev/null -D - "$url" -H "Authorization: Bearer $TOKEN" 2>/dev/null \
            | tr -d '\r' | grep -i '^content-length:' | awk '{print $2}')
  local problem
  problem=$(cat "$err")
  printf '%-46s status=%s bytes=%-7s content-length=%-7s %s\n' \
    "$label" "$code" "$bytes" "${declared:-none}" "${problem:+STDERR: $problem}"
  rm -f "$out" "$err"
}

echo "Orders list"
check "direct to order-service :18083" "$API/api/orders?page=0&size=5"
check "through the gateway :18080" "$API/api/orders?page=0&size=5"

echo ""
echo "Deliveries list"
check "direct to delivery-service :18085" "$OPS_PORT/api/deliveries?page=0&size=50"
check "through the gateway :18080" "$API/api/deliveries?page=0&size=50"

echo ""
echo "Smaller payload"
check "one order" "$API/api/orders/1"