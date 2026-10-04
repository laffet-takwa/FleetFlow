#!/usr/bin/env bash
# Raw framing of a real 200 response, with a valid token.
set -u
API=http://localhost:18080
TOKEN=$(curl -sS -X POST "$API/api/auth/login" -H 'Content-Type: application/json' \
  -d '{"email":"operations@fleetflow.local","password":"Password123!"}' \
  | node -e 'let r="";process.stdin.on("data",c=>r+=c);process.stdin.on("end",()=>console.log(JSON.parse(r).accessToken))')

echo "=== via gateway :18080 ==="
curl -sS -v -o /tmp/body-gw.txt "http://localhost:18080/api/orders/1" -H "Authorization: Bearer $TOKEN" 2>&1 \
  | grep -iE '^< ' | head -25
echo "bytes: $(wc -c < /tmp/body-gw.txt | tr -d ' ')"

echo ""
echo "=== direct to order-service :18083 ==="
curl -sS -v -o /tmp/body-direct.txt "http://localhost:18083/api/orders/1" -H "Authorization: Bearer $TOKEN" 2>&1 \
  | grep -iE '^< ' | head -25
echo "bytes: $(wc -c < /tmp/body-direct.txt | tr -d ' ')"

echo ""
echo "=== identical? ==="
if diff -q /tmp/body-gw.txt /tmp/body-direct.txt >/dev/null; then echo "yes"; else echo "NO"; fi