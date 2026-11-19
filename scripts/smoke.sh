#!/usr/bin/env bash
# Smoke-test the real stack: start it with docker compose, wait until the app's Restate endpoint is
# registered, run every api-tests/*.hurl file against it, then stop the stack.
#
#   ./scripts/smoke.sh          build, start, test, stop
#   ./scripts/smoke.sh --keep   leave the stack running afterwards (for a demo)
#
# Uses a local `hurl` when installed, otherwise the pinned Hurl image. Never runs `down -v`:
# the restate-data volume holds every charge.
set -euo pipefail

HURL_IMAGE="ghcr.io/orange-opensource/hurl:8.1.0"
KEEP=false
[[ "${1:-}" == "--keep" ]] && KEEP=true

cd "$(dirname "$0")/.."

stop() {
  if [[ "$KEEP" == false ]]; then
    echo "==> stopping the stack (volumes kept)"
    docker compose --progress quiet down
  fi
}
trap stop EXIT

echo "==> starting the stack"
started_at="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
docker compose --progress quiet up -d --build

echo "==> waiting for the Restate deployment to be registered"
for _ in $(seq 1 90); do
  if docker compose logs --since "$started_at" register 2>/dev/null | grep -q "deployment registered"; then
    registered=true
    break
  fi
  sleep 2
done
if [[ "${registered:-false}" != true ]]; then
  echo "the deployment was not registered within 3 minutes; recent logs:" >&2
  docker compose logs --tail=40 app register >&2
  exit 1
fi

echo "==> running api-tests/*.hurl"
if command -v hurl >/dev/null 2>&1; then
  hurl --test --variable base_url=http://localhost:8081 api-tests/*.hurl
else
  network="$(docker compose ps -q app | xargs docker inspect -f '{{range $k, $v := .NetworkSettings.Networks}}{{$k}}{{end}}')"
  docker run --rm --network "$network" -v "$PWD/api-tests:/api-tests:ro" "$HURL_IMAGE" \
    --test --variable base_url=http://app:8081 --glob '/api-tests/*.hurl'
fi
