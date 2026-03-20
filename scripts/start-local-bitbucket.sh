#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
STACK_DIR="$ROOT_DIR/infra/bitbucket-local"
COMPOSE_FILE="$STACK_DIR/docker-compose.yml"

if ! command -v docker >/dev/null 2>&1; then
  echo "docker is required but not found in PATH"
  exit 1
fi

if ! docker compose version >/dev/null 2>&1; then
  echo "docker compose is required but not available"
  exit 1
fi

echo "Starting local Bitbucket stack..."
docker compose -f "$COMPOSE_FILE" up -d

echo "Waiting for Bitbucket HTTP endpoint on http://127.0.0.1:7990 ..."
ready=0
for _ in $(seq 1 120); do
  if curl -sS -o /dev/null -w "%{http_code}" http://127.0.0.1:7990 | grep -Eq "^(200|302|401)$"; then
    ready=1
    break
  fi
  sleep 10
done

if [[ "$ready" -eq 1 ]]; then
  echo "Bitbucket is responding on http://127.0.0.1:7990"
else
  echo "Bitbucket did not become reachable in time."
  echo "Check logs with: docker compose -f $COMPOSE_FILE logs -f bitbucket"
  exit 1
fi

echo
echo "Next steps:"
echo "1) Open http://127.0.0.1:7990 and complete first-run setup (license + admin account)."
echo "2) Upload plugin JAR from target/ai-code-reviewer-1.0.0.jar via Manage apps."
echo "3) Configure Ollama URL/model in AI Code Reviewer admin page."
echo "4) Export vars and run scripts/run-all-7-tests.sh"
echo
echo "Example env export for test runner:"
echo "export BITBUCKET_BASE_URL=http://127.0.0.1:7990"
echo "export BITBUCKET_AUTH=<adminUser>:<adminPasswordOrToken>"
echo "export PROJECT_KEY=<yourProjectKey>"
echo "export REPO_SLUG=<yourRepoSlug>"
echo "export PR_ID=<anOpenPRId>"
