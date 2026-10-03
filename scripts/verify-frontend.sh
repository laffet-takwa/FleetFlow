#!/usr/bin/env bash
# Verifies the browser path end to end: the SPA is served by nginx, and the same
# nginx origin proxies /api to the gateway. This is the exact path a browser takes.
set -u
FAIL=0

check() {
  local label="$1" expected="$2" url="$3" method="${4:-GET}" body="${5:-}"
  local sink; sink="$(mktemp 2>/dev/null || echo /tmp/ff-check.txt)"
  local args=(-sS -o "$sink" -w '%{http_code}' -X "$method" "$url" -H 'Content-Type: application/json')
  [[ -n "$body" ]] && args+=(--data-binary "$body")
  local code; code="$(curl "${args[@]}" 2>/dev/null)"
  rm -f "$sink" 2>/dev/null
  if [[ "$code" == "$expected" ]]; then
    printf '  PASS  %-46s %s\n' "$label" "$code"
  else
    printf '  FAIL  %-46s got %s, expected %s\n' "$label" "$code" "$expected"
    FAIL=1
  fi
}

SPA="${SPA:-http://localhost:18088}"

echo "Verifying the browser path through nginx (${SPA})"
check "SPA index served"                 200 "${SPA}/"
check "SPA assets reachable"             200 "${SPA}/favicon.svg"
check "nginx health endpoint"            200 "${SPA}/healthz"
check "api proxied: login"               200 "${SPA}/api/auth/login" POST \
      '{"email":"admin@fleetflow.local","password":"Password123!"}'
check "api proxied: catalogue requires auth" 401 "${SPA}/api/products"
check "api proxied: bad login rejected"  401 "${SPA}/api/auth/login" POST \
      '{"email":"admin@fleetflow.local","password":"wrong"}'
# An unknown /api path answers 401, not 404: the edge policy requires authentication for
# everything it does not explicitly permit, and that runs before routing. Telling an
# anonymous caller "this endpoint does not exist" would leak which routes are real.
check "api proxied: unknown path stays unauthorised" 401 "${SPA}/api/nope"
check "api proxied: open docs are reachable" 200 "${SPA}/v3/api-docs/auth-service"
check "health proxied from the gateway"  200 "${SPA}/actuator/health"

if [[ "$FAIL" -eq 0 ]]; then
  echo "All browser-path checks passed."
else
  echo "One or more browser-path checks failed."
fi
exit "$FAIL"