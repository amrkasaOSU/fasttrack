#!/usr/bin/env bash
# Runs the k6 load test at three concurrency levels and writes the summaries
# into k6/out/ for RESULTS.md.
#
#   ./scripts/run-load-tests.sh                 # against localhost:8080
#   BASE_URL=http://localhost:8222 ./scripts/run-load-tests.sh
set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
DURATION="${DURATION:-30s}"
OUT_DIR="$(cd "$(dirname "$0")/.." && pwd)/k6/out"
mkdir -p "$OUT_DIR"

if ! curl -sf "$BASE_URL/actuator/health" > /dev/null; then
  echo "Backend is not responding at $BASE_URL - start it with: docker compose up -d" >&2
  exit 1
fi

# A warm-up pass first. Without it the first measured run carries the JVM's
# JIT compilation and the connection pool filling up, which showed as a worse
# p99 at 50 users than at 100 - an artefact, not a finding.
echo "=== warm-up (10 users, 15s, not recorded) ==="
k6 run --quiet \
  -e "BASE_URL=$BASE_URL" -e "VUS=10" -e "DURATION=15s" \
  "$(dirname "$0")/../k6/load-test.js" > /dev/null 2>&1 || true

for vus in 50 100 250; do
  echo
  echo "=== $vus concurrent users, $DURATION ==="
  k6 run \
    -e "BASE_URL=$BASE_URL" \
    -e "VUS=$vus" \
    -e "DURATION=$DURATION" \
    --summary-export "$OUT_DIR/summary-${vus}vu.json" \
    "$(dirname "$0")/../k6/load-test.js" \
    | tee "$OUT_DIR/run-${vus}vu.txt"
done

echo
echo "Summaries written to $OUT_DIR"
