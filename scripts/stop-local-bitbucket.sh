#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
COMPOSE_FILE="$ROOT_DIR/infra/bitbucket-local/docker-compose.yml"
MODE="${1:-keep-data}"

if [[ "$MODE" == "wipe-data" ]]; then
  docker compose -f "$COMPOSE_FILE" down -v
  echo "Stopped local Bitbucket stack and removed volumes."
else
  docker compose -f "$COMPOSE_FILE" down
  echo "Stopped local Bitbucket stack (data preserved)."
fi
