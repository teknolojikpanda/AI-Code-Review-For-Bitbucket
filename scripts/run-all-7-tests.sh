#!/usr/bin/env bash
set -euo pipefail

# Runs all 7 requested test categories for AI Code Reviewer plugin.
#
# Categories:
# 1) Unit tests
# 2) Integration tests
# 3) Functional tests (admin/runtime API behavior)
# 4) End-to-end tests (manual review trigger + progress visibility)
# 5) Acceptance tests (basic success criteria checks)
# 6) Performance tests (guardrails load script)
# 7) Smoke tests (build + Ollama + connectivity)
#
# Required environment variables for live Bitbucket tests:
#   BITBUCKET_BASE_URL   e.g. http://127.0.0.1:7990
#   BITBUCKET_AUTH       e.g. admin:admin or admin:token
#   PROJECT_KEY          e.g. PROJ
#   REPO_SLUG            e.g. my-repo
#   PR_ID                e.g. 42
#
# Optional:
#   OLLAMA_URL           default: http://127.0.0.1:11434
#   OLLAMA_MODEL         default: deepseek-coder-v2:16B
#   PERF_DURATION        default: 60
#   PERF_CONCURRENCY     default: 2

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
OLLAMA_URL="${OLLAMA_URL:-http://127.0.0.1:11434}"
OLLAMA_MODEL="${OLLAMA_MODEL:-deepseek-coder-v2:16B}"
OLLAMA_CONTAINER_URL="${OLLAMA_CONTAINER_URL:-http://host.docker.internal:11434}"
PERF_DURATION="${PERF_DURATION:-60}"
PERF_CONCURRENCY="${PERF_CONCURRENCY:-2}"

PASS=0
FAIL=0
SKIP=0

report_pass() { echo "[PASS] $1"; PASS=$((PASS+1)); }
report_fail() { echo "[FAIL] $1"; FAIL=$((FAIL+1)); }
report_skip() { echo "[SKIP] $1"; SKIP=$((SKIP+1)); }

run_cmd() {
  local title="$1"
  shift
  echo
  echo "== $title =="
  if "$@"; then
    report_pass "$title"
  else
    report_fail "$title"
  fi
}

api_call() {
  local method="$1"
  local path="$2"
  local data="${3:-}"
  local url="${BITBUCKET_BASE_URL%/}${path}"

  if [[ -n "$data" ]]; then
    curl -sS -u "$BITBUCKET_AUTH" -X "$method" "$url" \
      -H "Content-Type: application/json" \
      -d "$data" \
      -o /tmp/ai-reviewer-api-body.json \
      -w "%{http_code}" > /tmp/ai-reviewer-api-code.txt
  else
    curl -sS -u "$BITBUCKET_AUTH" -X "$method" "$url" \
      -o /tmp/ai-reviewer-api-body.json \
      -w "%{http_code}" > /tmp/ai-reviewer-api-code.txt
  fi

  cat /tmp/ai-reviewer-api-code.txt
}

smoke_tests() {
  echo
  echo "== 7) Smoke testing =="

  if [[ -f "$ROOT_DIR/target/ai-code-reviewer-1.0.0.jar" ]]; then
    report_pass "Smoke: plugin JAR exists"
  else
    if (cd "$ROOT_DIR" && /opt/homebrew/bin/mvn clean package -DskipTests >/dev/null); then
      report_pass "Smoke: plugin builds successfully"
    else
      report_fail "Smoke: plugin build"
    fi
  fi

  if curl -sS "${OLLAMA_URL%/}/api/tags" >/tmp/ollama-tags.json; then
    report_pass "Smoke: Ollama endpoint reachable"
  else
    report_fail "Smoke: Ollama endpoint reachable"
  fi

  if curl -sS -X POST "${OLLAMA_URL%/}/api/generate" \
    -H "Content-Type: application/json" \
    -d "{\"model\":\"${OLLAMA_MODEL}\",\"prompt\":\"Say ok\",\"stream\":false}" \
    >/tmp/ollama-generate.json; then
    report_pass "Smoke: Ollama model inference"
  else
    report_fail "Smoke: Ollama model inference"
  fi
}

unit_tests() {
  run_cmd "1) Unit tests" bash -lc "cd '$ROOT_DIR' && /opt/homebrew/bin/mvn -Dtest='*Test,!*IntegrationTest' test"
}

integration_tests() {
  run_cmd "2) Integration tests" bash -lc "cd '$ROOT_DIR' && /opt/homebrew/bin/mvn -Dtest='*IntegrationTest' test"
}

live_tests_precheck() {
  if [[ -z "${BITBUCKET_BASE_URL:-}" || -z "${BITBUCKET_AUTH:-}" || -z "${PROJECT_KEY:-}" || -z "${REPO_SLUG:-}" || -z "${PR_ID:-}" ]]; then
    return 1
  fi
  return 0
}

functional_tests() {
  echo
  echo "== 3) Functional tests =="

  local code
  code="$(api_call GET "/rest/ai-reviewer/1.0/monitoring/runtime")"
  [[ "$code" == "200" ]] && report_pass "Functional: monitoring runtime" || report_fail "Functional: monitoring runtime (HTTP $code)"

  code="$(api_call GET "/rest/ai-reviewer/1.0/progress/admin/queue")"
  [[ "$code" == "200" ]] && report_pass "Functional: queue endpoint" || report_fail "Functional: queue endpoint (HTTP $code)"

  code="$(api_call POST "/rest/ai-reviewer/1.0/config/test-connection" "{\"ollamaUrl\":\"${OLLAMA_CONTAINER_URL}\",\"ollamaModel\":\"${OLLAMA_MODEL}\"}")"
  [[ "$code" == "200" ]] && report_pass "Functional: config test-connection" || report_fail "Functional: config test-connection (HTTP $code)"
}

e2e_tests() {
  echo
  echo "== 4) End-to-end tests =="

  local payload
  local code
  payload="{\"projectKey\":\"$PROJECT_KEY\",\"repositorySlug\":\"$REPO_SLUG\",\"pullRequestId\":$PR_ID,\"force\":true,\"treatAsUpdate\":true}"
  code="$(api_call POST "/rest/ai-reviewer/1.0/history/manual" "$payload")"
  if [[ "$code" != "200" && "$code" != "202" ]]; then
    report_fail "E2E: manual review trigger (HTTP $code)"
    return
  fi
  report_pass "E2E: manual review trigger"

  local ok=0
  for _ in $(seq 1 20); do
    code="$(api_call GET "/rest/ai-reviewer/1.0/progress/$PROJECT_KEY/$REPO_SLUG/$PR_ID")"
    if [[ "$code" == "200" || "$code" == "404" ]]; then
      ok=1
      break
    fi
    sleep 3
  done

  if [[ "$ok" -eq 1 ]]; then
    report_pass "E2E: progress endpoint responds during/after run"
  else
    report_fail "E2E: progress endpoint did not stabilize"
  fi
}

acceptance_tests() {
  echo
  echo "== 5) Acceptance testing =="

  local code
  code="$(api_call GET "/rest/ai-reviewer/1.0/progress/$PROJECT_KEY/$REPO_SLUG/$PR_ID/history?limit=1&offset=0")"
  [[ "$code" == "200" ]] && report_pass "Acceptance: PR history endpoint" || report_fail "Acceptance: PR history endpoint (HTTP $code)"

  code="$(api_call GET "/rest/ai-reviewer/1.0/history?limit=1&offset=0")"
  [[ "$code" == "200" ]] && report_pass "Acceptance: global history endpoint" || report_fail "Acceptance: global history endpoint (HTTP $code)"
}

performance_tests() {
  echo
  echo "== 6) Performance testing =="

  if ! python3 -c "import requests" >/dev/null 2>&1; then
    echo "Installing Python dependency: requests"
    python3 -m pip install --user requests >/dev/null
  fi

  if (cd "$ROOT_DIR" && python3 perf/load-test.py --base-url "$BITBUCKET_BASE_URL" --auth "$BITBUCKET_AUTH" --duration "$PERF_DURATION" --concurrency "$PERF_CONCURRENCY"); then
    report_pass "Performance: guardrails load test"
  else
    report_fail "Performance: guardrails load test"
  fi
}

main() {
  smoke_tests
  unit_tests
  integration_tests

  if live_tests_precheck; then
    functional_tests
    e2e_tests
    acceptance_tests
    performance_tests
  else
    report_skip "3) Functional tests (missing BITBUCKET_* and PR variables)"
    report_skip "4) End-to-end tests (missing BITBUCKET_* and PR variables)"
    report_skip "5) Acceptance tests (missing BITBUCKET_* and PR variables)"
    report_skip "6) Performance tests (missing BITBUCKET_* and PR variables)"
  fi

  echo
  echo "=== Final Summary ==="
  echo "Passed:  $PASS"
  echo "Failed:  $FAIL"
  echo "Skipped: $SKIP"

  if [[ "$FAIL" -gt 0 ]]; then
    exit 1
  fi
}

main "$@"
