#!/usr/bin/env bash
# Generates a few minutes of realistic traffic against a running stack so the Grafana dashboard has data:
# steady transfers, some rejections, a velocity burst and a large transfer. Usage: deploy/traffic.sh [rounds]
set -euo pipefail
API=${API:-http://localhost:8080/api/v1}
J='Content-Type: application/json'
ROUNDS=${1:-150}

open_account() {
  curl -sf -H "$J" -d "{\"owner\":\"$1\",\"currency\":\"EUR\"}" "$API/accounts" | sed -E 's/.*"id":"([^"]+)".*/\1/'
}
transfer() {
  curl -s -o /dev/null -w '%{http_code}\n' -H "$J" -H "Idempotency-Key: $(uuidgen)" \
    -d "{\"sourceAccountId\":\"$1\",\"targetAccountId\":\"$2\",\"amount\":{\"amount\":\"$3\",\"currency\":\"EUR\"}}" \
    "$API/transfers"
}

accounts=()
for name in Alice Bob Carol Dave Erin; do accounts+=("$(open_account "$name")"); done
for id in "${accounts[@]}"; do
  curl -sf -o /dev/null -H "$J" -H "Idempotency-Key: seed-$id" \
    -d '{"amount":{"amount":"5000.00","currency":"EUR"}}' "$API/accounts/$id/deposits"
done

for round in $(seq 1 "$ROUNDS"); do
  from=${accounts[$((RANDOM % 5))]}
  to=${accounts[$((RANDOM % 5))]}
  [ "$from" = "$to" ] && to=${accounts[$(((RANDOM + 1) % 5))]}
  case $((round % 25)) in
    0) transfer "$from" "$to" 99999.00 ;;            # rejected: insufficient funds
    7) transfer "$from" "$from" 1.00 ;;              # rejected: same account
    13) transfer "${accounts[0]}" "$to" 6000.00 ;;   # large amount, if the funds are there
    *) transfer "$from" "$to" "$((RANDOM % 90 + 1)).$((RANDOM % 90 + 10))" ;;
  esac
done | sort | uniq -c
