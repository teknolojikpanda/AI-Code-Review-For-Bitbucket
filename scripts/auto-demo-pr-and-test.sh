#!/usr/bin/env bash
set -euo pipefail
export GIT_TERMINAL_PROMPT=0

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
BITBUCKET_BASE_URL="${BITBUCKET_BASE_URL:-http://127.0.0.1:7990}"
BITBUCKET_AUTH="${BITBUCKET_AUTH:-}"
PROJECT_KEY="${PROJECT_KEY:-DEMO}"
PROJECT_NAME="${PROJECT_NAME:-Demo Project}"
REPO_SLUG="${REPO_SLUG:-demo-repo}"
REPO_NAME="${REPO_NAME:-$REPO_SLUG}"
MAIN_BRANCH="${MAIN_BRANCH:-master}"
FEATURE_BRANCH="${FEATURE_BRANCH:-feature/ai-reviewer-demo-$(date +%s)}"
WORK_DIR="${WORK_DIR:-$ROOT_DIR/.tmp/bitbucket-demo}"
PLUGIN_KEY="${PLUGIN_KEY:-com.teknolojikpanda.bitbucket.ai-code-reviewer}"
PLUGIN_JAR="${PLUGIN_JAR:-$ROOT_DIR/target/ai-code-reviewer-1.0.0.jar}"
OLLAMA_CONTAINER_URL="${OLLAMA_CONTAINER_URL:-http://host.docker.internal:11434}"
OLLAMA_MODEL="${OLLAMA_MODEL:-deepseek-coder-v2:16B}"
FALLBACK_MODEL="${FALLBACK_MODEL:-qwen3-vl:32b}"

if [[ -z "$BITBUCKET_AUTH" ]]; then
  echo "BITBUCKET_AUTH is required (format: user:passwordOrToken)."
  exit 1
fi

mkdir -p "$WORK_DIR"

bb_api() {
  local method="$1"
  local path="$2"
  local data="${3:-}"
  local url="${BITBUCKET_BASE_URL%/}${path}"

  if [[ -n "$data" ]]; then
    curl -sS -u "$BITBUCKET_AUTH" -X "$method" "$url" \
      -H "Content-Type: application/json" \
      -d "$data" \
      -o /tmp/bb_api_body.json \
      -w "%{http_code}" > /tmp/bb_api_code.txt
  else
    curl -sS -u "$BITBUCKET_AUTH" -X "$method" "$url" \
      -o /tmp/bb_api_body.json \
      -w "%{http_code}" > /tmp/bb_api_code.txt
  fi

  cat /tmp/bb_api_code.txt
}

ensure_bitbucket_running() {
  "$ROOT_DIR/scripts/start-local-bitbucket.sh" >/dev/null

  local headers
  headers="$(curl -sSI "${BITBUCKET_BASE_URL%/}" || true)"
  if echo "$headers" | grep -qi '^Location: .*\/setup'; then
    echo "Bitbucket is in setup mode at ${BITBUCKET_BASE_URL%/}/setup."
    echo "Complete first-run setup (license + admin user), then rerun this script."
    exit 1
  fi
}

ensure_auth_works() {
  local code
  code="$(bb_api GET "/rest/api/1.0/application-properties")"
  if [[ "$code" != "200" ]]; then
    echo "Authentication failed or Bitbucket API unavailable (HTTP $code)."
    echo "Response:"
    cat /tmp/bb_api_body.json || true
    exit 1
  fi
}

ensure_plugin_installed() {
  if [[ ! -f "$PLUGIN_JAR" ]]; then
    echo "Plugin JAR not found at $PLUGIN_JAR, building..."
    (cd "$ROOT_DIR" && /opt/homebrew/bin/mvn clean package -DskipTests >/dev/null)
  fi

  local list_code
  list_code="$(curl -sS -u "$BITBUCKET_AUTH" \
    -H "Accept: application/vnd.atl.plugins.installed+json" \
    "${BITBUCKET_BASE_URL%/}/rest/plugins/1.0/?os_authType=basic" \
    -o /tmp/upm_plugins.json -w "%{http_code}")"

  if [[ "$list_code" != "200" ]]; then
    echo "Failed to query installed plugins (HTTP $list_code)."
    exit 1
  fi

  if grep -q "$PLUGIN_KEY" /tmp/upm_plugins.json; then
    echo "Plugin $PLUGIN_KEY already installed."
    return
  fi

  local upm_token
  upm_token="$(curl -sS -u "$BITBUCKET_AUTH" -D - \
    -H "Accept: application/vnd.atl.plugins.installed+json" \
    "${BITBUCKET_BASE_URL%/}/rest/plugins/1.0/?os_authType=basic" \
    -o /tmp/upm_plugins_refresh.json | awk -F': ' 'tolower($1)=="upm-token" {gsub("\r", "", $2); print $2}' | tail -n1)"

  if [[ -z "$upm_token" ]]; then
    echo "Failed to obtain UPM token for plugin upload."
    exit 1
  fi

  local install_code
  install_code="$(curl -sS -u "$BITBUCKET_AUTH" \
    -H "upm-token: $upm_token" \
    -F "plugin=@$PLUGIN_JAR" \
    "${BITBUCKET_BASE_URL%/}/rest/plugins/1.0/?token=$upm_token" \
    -o /tmp/upm_install.json -w "%{http_code}")"

  if [[ "$install_code" != "200" && "$install_code" != "202" ]]; then
    echo "Plugin upload failed (HTTP $install_code)."
    cat /tmp/upm_install.json || true
    exit 1
  fi

  echo "Plugin uploaded; waiting for AI reviewer REST endpoints..."
  for _ in $(seq 1 60); do
    local code
    code="$(bb_api GET "/rest/ai-reviewer/1.0/config")"
    if [[ "$code" == "200" ]]; then
      echo "Plugin REST endpoints are available."
      return
    fi
    sleep 2
  done

  echo "Plugin did not become ready in time."
  exit 1
}

configure_plugin_for_local_ollama() {
  local payload
  payload="{\"ollamaUrl\":\"$OLLAMA_CONTAINER_URL\",\"ollamaModel\":\"$OLLAMA_MODEL\",\"fallbackModel\":\"$FALLBACK_MODEL\"}"

  local code
  code="$(bb_api PUT "/rest/ai-reviewer/1.0/config" "$payload")"
  if [[ "$code" != "200" ]]; then
    echo "Failed to update AI reviewer config for local Ollama (HTTP $code)."
    cat /tmp/bb_api_body.json || true
    exit 1
  fi

  echo "Configured AI reviewer to use Ollama at $OLLAMA_CONTAINER_URL"
}

ensure_project() {
  local code
  code="$(bb_api GET "/rest/api/1.0/projects/$PROJECT_KEY")"
  if [[ "$code" == "200" ]]; then
    echo "Project $PROJECT_KEY already exists."
    return
  fi

  local payload
  payload="{\"key\":\"$PROJECT_KEY\",\"name\":\"$PROJECT_NAME\",\"public\":false}"
  code="$(bb_api POST "/rest/api/1.0/projects" "$payload")"
  if [[ "$code" != "201" ]]; then
    echo "Failed to create project $PROJECT_KEY (HTTP $code)."
    cat /tmp/bb_api_body.json || true
    exit 1
  fi
  echo "Created project $PROJECT_KEY."
}

ensure_repo() {
  local code
  code="$(bb_api GET "/rest/api/1.0/projects/$PROJECT_KEY/repos/$REPO_SLUG")"
  if [[ "$code" == "200" ]]; then
    echo "Repository $PROJECT_KEY/$REPO_SLUG already exists."
    return
  fi

  local payload
  payload="{\"name\":\"$REPO_SLUG\",\"scmId\":\"git\",\"forkable\":true}"
  code="$(bb_api POST "/rest/api/1.0/projects/$PROJECT_KEY/repos" "$payload")"
  if [[ "$code" == "201" ]]; then
    # Keep default slug if creation succeeds.
    echo "Created repository $PROJECT_KEY/$REPO_SLUG."
    return
  fi

  if [[ "$code" == "409" ]]; then
    # 409 means requested URL slug is already in use; try to reuse it first.
    code="$(bb_api GET "/rest/api/1.0/projects/$PROJECT_KEY/repos/$REPO_SLUG")"
    if [[ "$code" == "200" ]]; then
      echo "Repository URL already existed; reusing $PROJECT_KEY/$REPO_SLUG."
      return
    fi

    # If slug is still unavailable, generate a unique slug/name and retry once.
    local suffix
    suffix="$(date +%s)"
    REPO_SLUG="${REPO_SLUG}-${suffix}"
    REPO_NAME="$REPO_SLUG"
    payload="{\"name\":\"$REPO_SLUG\",\"scmId\":\"git\",\"forkable\":true}"
    code="$(bb_api POST "/rest/api/1.0/projects/$PROJECT_KEY/repos" "$payload")"
    if [[ "$code" == "201" ]]; then
      echo "Created repository with fallback slug $PROJECT_KEY/$REPO_SLUG."
      return
    fi
  fi

  echo "Failed to create repository in $PROJECT_KEY (HTTP $code)."
  cat /tmp/bb_api_body.json || true
  exit 1
}

seed_and_branch_repo() {
  local repo_dir="$WORK_DIR/$REPO_SLUG"
  rm -rf "$repo_dir"

  local auth_b64
  auth_b64="$(printf '%s' "$BITBUCKET_AUTH" | base64)"
  local clone_url="${BITBUCKET_BASE_URL%/}/scm/${PROJECT_KEY}/${REPO_SLUG}.git"

  git -c http.extraHeader="Authorization: Basic $auth_b64" clone "$clone_url" "$repo_dir"
  cd "$repo_dir"

  git config user.name "AI Reviewer Bot"
  git config user.email "ai-reviewer-bot@example.local"

  if git ls-remote --exit-code --heads origin "$MAIN_BRANCH" >/dev/null 2>&1; then
    git checkout -B "$MAIN_BRANCH" "origin/$MAIN_BRANCH"
  else
    git checkout -b "$MAIN_BRANCH"
  fi

  mkdir -p src/api src/models src/repositories src/services src/utils tests docs

  cat > src/models/order.py <<'PY'
from dataclasses import dataclass
from datetime import datetime
from typing import List


@dataclass(frozen=True)
class OrderItem:
    sku: str
    unit_price: float
    quantity: int


@dataclass(frozen=True)
class Order:
    order_id: str
    customer_id: str
    items: List[OrderItem]
    created_at: datetime
PY

  cat > src/services/validators.py <<'PY'
from typing import Iterable


def validate_customer_id(customer_id: str) -> bool:
    if not customer_id:
        return False
    if len(customer_id) < 6:
        return False
    return customer_id.isalnum()


def validate_items(items: Iterable[dict]) -> bool:
    for item in items:
        if item.get("quantity", 0) <= 0:
            return False
        if item.get("unit_price", 0.0) < 0:
            return False
    return True
PY

  cat > src/services/pricing.py <<'PY'
from decimal import Decimal, ROUND_HALF_UP


def calculate_subtotal(items: list[dict]) -> Decimal:
    subtotal = Decimal("0")
    for item in items:
        price = Decimal(str(item["unit_price"]))
        qty = Decimal(str(item["quantity"]))
        subtotal += price * qty
    return subtotal.quantize(Decimal("0.01"), rounding=ROUND_HALF_UP)


def apply_discount(subtotal: Decimal, discount_percent: int) -> Decimal:
    if discount_percent < 0 or discount_percent > 100:
        raise ValueError("discount_percent must be 0..100")
    discount = subtotal * (Decimal(discount_percent) / Decimal("100"))
    return (subtotal - discount).quantize(Decimal("0.01"), rounding=ROUND_HALF_UP)


def apply_tax(amount: Decimal, tax_percent: int) -> Decimal:
    if tax_percent < 0 or tax_percent > 30:
        raise ValueError("tax_percent must be 0..30")
    tax = amount * (Decimal(tax_percent) / Decimal("100"))
    return (amount + tax).quantize(Decimal("0.01"), rounding=ROUND_HALF_UP)
PY

  cat > src/repositories/in_memory_repo.py <<'PY'
from threading import Lock


class InMemoryOrderRepository:
    def __init__(self) -> None:
        self._orders: dict[str, dict] = {}
        self._lock = Lock()

    def save(self, order: dict) -> None:
        with self._lock:
            self._orders[order["order_id"]] = order

    def get(self, order_id: str) -> dict | None:
        with self._lock:
            return self._orders.get(order_id)

    def list_by_customer(self, customer_id: str) -> list[dict]:
        with self._lock:
            return [o for o in self._orders.values() if o["customer_id"] == customer_id]
PY

  cat > src/utils/cache.py <<'PY'
import time
from threading import Lock


class SimpleTTLCache:
    def __init__(self, ttl_seconds: int = 30) -> None:
        self._ttl = ttl_seconds
        self._store: dict[str, tuple[float, object]] = {}
        self._lock = Lock()

    def set(self, key: str, value: object) -> None:
        with self._lock:
            self._store[key] = (time.time(), value)

    def get(self, key: str) -> object | None:
        with self._lock:
            entry = self._store.get(key)
            if entry is None:
                return None
            created_at, value = entry
            if time.time() - created_at > self._ttl:
                del self._store[key]
                return None
            return value
PY

  cat > src/api/order_controller.py <<'PY'
from datetime import datetime, timezone
from uuid import uuid4

from src.repositories.in_memory_repo import InMemoryOrderRepository
from src.services.pricing import apply_discount, apply_tax, calculate_subtotal
from src.services.validators import validate_customer_id, validate_items
from src.utils.cache import SimpleTTLCache


class OrderController:
    def __init__(self) -> None:
        self.repo = InMemoryOrderRepository()
        self.cache = SimpleTTLCache(ttl_seconds=20)

    def create_order(self, payload: dict) -> dict:
        customer_id = payload.get("customer_id", "")
        items = payload.get("items", [])
        discount_percent = int(payload.get("discount_percent", 0))
        tax_percent = int(payload.get("tax_percent", 18))

        if not validate_customer_id(customer_id):
            raise ValueError("invalid customer_id")
        if not validate_items(items):
            raise ValueError("invalid items")

        subtotal = calculate_subtotal(items)
        discounted = apply_discount(subtotal, discount_percent)
        total = apply_tax(discounted, tax_percent)

        order = {
            "order_id": str(uuid4()),
            "customer_id": customer_id,
            "items": items,
            "subtotal": str(subtotal),
            "total": str(total),
            "created_at": datetime.now(timezone.utc).isoformat(),
        }

        self.repo.save(order)
        self.cache.set(order["order_id"], order)
        return order

    def get_order(self, order_id: str) -> dict | None:
        cached = self.cache.get(order_id)
        if cached is not None:
            return cached
        return self.repo.get(order_id)
PY

  cat > tests/test_pricing.py <<'PY'
from decimal import Decimal

from src.services.pricing import apply_discount, apply_tax, calculate_subtotal


def test_happy_path_pricing() -> None:
    items = [{"unit_price": 100.0, "quantity": 2}, {"unit_price": 50.0, "quantity": 1}]
    subtotal = calculate_subtotal(items)
    assert subtotal == Decimal("250.00")
    discounted = apply_discount(subtotal, 10)
    assert discounted == Decimal("225.00")
    total = apply_tax(discounted, 20)
    assert total == Decimal("270.00")
PY

  cat > tests/test_validators.py <<'PY'
from src.services.validators import validate_customer_id, validate_items


def test_customer_id_validation() -> None:
    assert validate_customer_id("ABC123") is True
    assert validate_customer_id("bad") is False


def test_item_validation() -> None:
    assert validate_items([{"unit_price": 1.0, "quantity": 1}]) is True
    assert validate_items([{"unit_price": 1.0, "quantity": 0}]) is False
PY

  cat > docs/scenario-matrix.md <<'MD'
# Scenario Matrix

This demo repository is intentionally designed for broad AI review coverage.

- Input validation and malformed payload handling
- Pricing arithmetic and rounding behavior
- Cache staleness and consistency
- Repository access patterns and data integrity
- API-level error handling
- Security-oriented regression cases on feature branches
- Lightweight test coverage to detect regressions
MD

  cat > README.md <<'MD'
# Demo Repository (Advanced)

This repository is generated by automation for AI Code Reviewer testing.

## Purpose

It contains multiple modules and scenario-rich code to exercise review quality:

- validation
- pricing logic
- API orchestration
- storage behavior
- cache behavior
- regression-oriented feature changes

## Layout

- src/api: API orchestration
- src/services: business logic
- src/repositories: persistence abstractions
- src/utils: utility components
- tests: baseline tests
- docs: scenario matrix
MD

  git add .
  if ! git diff --cached --quiet; then
    git commit -m "chore: seed demo repository"
    if ! git -c http.extraHeader="Authorization: Basic $auth_b64" push -u origin "$MAIN_BRANCH"; then
      git -c http.extraHeader="Authorization: Basic $auth_b64" pull --rebase origin "$MAIN_BRANCH" || true
      git -c http.extraHeader="Authorization: Basic $auth_b64" push -u origin "$MAIN_BRANCH"
    fi
  else
    echo "No seed changes to commit on $MAIN_BRANCH."
  fi

  if git ls-remote --exit-code --heads origin "$FEATURE_BRANCH" >/dev/null 2>&1; then
    git checkout -B "$FEATURE_BRANCH" "origin/$FEATURE_BRANCH"
  else
    git checkout -B "$FEATURE_BRANCH" "$MAIN_BRANCH"
  fi

  # Inject realistic regressions for AI review signal quality.
  cat > src/services/validators.py <<'PY'
from typing import Iterable


def validate_customer_id(customer_id: str) -> bool:
    # Regression: overly permissive validation accepts very short ids
    if not customer_id:
        return False
    return True


def validate_items(items: Iterable[dict]) -> bool:
    # Regression: negative quantities/prices are accidentally accepted
    for item in items:
        if item.get("quantity", 0) == 0:
            return False
    return True
PY

  cat > src/services/pricing.py <<'PY'
from decimal import Decimal


def calculate_subtotal(items: list[dict]) -> Decimal:
    subtotal = Decimal("0")
    for item in items:
        subtotal += Decimal(str(item["unit_price"])) * Decimal(str(item["quantity"]))
    return subtotal


def apply_discount(subtotal: Decimal, discount_percent: int) -> Decimal:
    # Regression: incorrect discount math (expects 0..100 but applies as multiplier)
    return subtotal - (subtotal * Decimal(discount_percent))


def apply_tax(amount: Decimal, tax_percent: int) -> Decimal:
    # Regression: can divide by zero when tax_percent == 0
    return amount + (amount / Decimal(tax_percent))
PY

  cat > src/utils/cache.py <<'PY'
import time


class SimpleTTLCache:
    def __init__(self, ttl_seconds: int = 30) -> None:
        self._ttl = ttl_seconds
        self._store: dict[str, tuple[float, object]] = {}

    def set(self, key: str, value: object) -> None:
        # Regression: lock removed, race conditions possible under concurrent requests
        self._store[key] = (time.time(), value)

    def get(self, key: str) -> object | None:
        entry = self._store.get(key)
        if entry is None:
            return None
        created_at, value = entry
        if time.time() - created_at > self._ttl:
            self._store.pop(key, None)
            return None
        return value
PY

  cat > src/api/order_controller.py <<'PY'
from datetime import datetime
from uuid import uuid4

from src.repositories.in_memory_repo import InMemoryOrderRepository
from src.services.pricing import apply_discount, apply_tax, calculate_subtotal
from src.services.validators import validate_customer_id, validate_items
from src.utils.cache import SimpleTTLCache


class OrderController:
    def __init__(self) -> None:
        self.repo = InMemoryOrderRepository()
        self.cache = SimpleTTLCache(ttl_seconds=20)

    def create_order(self, payload: dict) -> dict:
        # Regression: sensitive payload logging
        print("DEBUG create_order payload=", payload)

        customer_id = payload.get("customer_id", "")
        items = payload.get("items", [])
        discount_percent = int(payload.get("discount_percent", 0))
        tax_percent = int(payload.get("tax_percent", 0))

        if not validate_customer_id(customer_id):
            raise ValueError("invalid customer_id")
        if not validate_items(items):
            raise ValueError("invalid items")

        try:
            subtotal = calculate_subtotal(items)
            discounted = apply_discount(subtotal, discount_percent)
            total = apply_tax(discounted, tax_percent)
        except Exception:
            # Regression: swallows root cause, hides production diagnostics
            total = 0
            subtotal = 0

        order = {
            "order_id": str(uuid4()),
            "customer_id": customer_id,
            "items": items,
            "subtotal": str(subtotal),
            "total": str(total),
            "created_at": datetime.utcnow().isoformat(),
        }

        self.repo.save(order)
        self.cache.set(order["order_id"], order)
        return order

    def get_order(self, order_id: str) -> dict | None:
        return self.cache.get(order_id) or self.repo.get(order_id)
PY

  cat > docs/feature-regressions.md <<'MD'
# Feature Branch Regressions

This branch intentionally introduces defects for review signal:

- weakened validation rules
- arithmetic defects in discount/tax
- race-prone cache updates
- sensitive logging and broad exception swallowing
MD

  git add src/services/validators.py src/services/pricing.py src/utils/cache.py src/api/order_controller.py docs/feature-regressions.md
  if ! git diff --cached --quiet; then
    git commit -m "feat: inject multi-scenario regressions for AI review"
    if ! git -c http.extraHeader="Authorization: Basic $auth_b64" push -u origin "$FEATURE_BRANCH"; then
      git -c http.extraHeader="Authorization: Basic $auth_b64" push -u origin "$FEATURE_BRANCH" --force-with-lease
    fi
  else
    echo "No feature changes to commit on $FEATURE_BRANCH."
  fi
}

open_pr() {
  local payload
  payload="{\"title\":\"Demo PR for AI Code Reviewer\",\"description\":\"Automated PR for local testing\",\"state\":\"OPEN\",\"open\":true,\"closed\":false,\"fromRef\":{\"id\":\"refs/heads/$FEATURE_BRANCH\",\"repository\":{\"slug\":\"$REPO_SLUG\",\"project\":{\"key\":\"$PROJECT_KEY\"}}},\"toRef\":{\"id\":\"refs/heads/$MAIN_BRANCH\",\"repository\":{\"slug\":\"$REPO_SLUG\",\"project\":{\"key\":\"$PROJECT_KEY\"}}}}"

  local code
  code="$(bb_api POST "/rest/api/1.0/projects/$PROJECT_KEY/repos/$REPO_SLUG/pull-requests" "$payload")"
  if [[ "$code" == "201" ]]; then
    local pr_id
    pr_id="$(grep -o '"id"[[:space:]]*:[[:space:]]*[0-9]\+' /tmp/bb_api_body.json | head -n1 | sed 's/[^0-9]//g')"
    echo "$pr_id"
    return
  fi

  # If PR already exists, fetch first open PR for this source branch.
  code="$(bb_api GET "/rest/api/1.0/projects/$PROJECT_KEY/repos/$REPO_SLUG/pull-requests?state=OPEN&at=refs/heads/$FEATURE_BRANCH&limit=25")"
  if [[ "$code" != "200" ]]; then
    echo "Failed to create or discover PR (HTTP $code)."
    cat /tmp/bb_api_body.json || true
    exit 1
  fi

  local pr_id
  pr_id="$(grep -o '"id"[[:space:]]*:[[:space:]]*[0-9]\+' /tmp/bb_api_body.json | head -n1 | sed 's/[^0-9]//g')"
  if [[ -z "$pr_id" ]]; then
    echo "Could not determine PR id."
    cat /tmp/bb_api_body.json || true
    exit 1
  fi

  echo "$pr_id"
}

run_all_tests() {
  local pr_id="$1"
  export BITBUCKET_BASE_URL
  export BITBUCKET_AUTH
  export PROJECT_KEY
  export REPO_SLUG
  export PR_ID="$pr_id"

  echo "Running all 7 test categories with PR_ID=$PR_ID ..."
  "$ROOT_DIR/scripts/run-all-7-tests.sh"
}

main() {
  ensure_bitbucket_running
  ensure_auth_works
  ensure_plugin_installed
  configure_plugin_for_local_ollama
  ensure_project
  ensure_repo
  seed_and_branch_repo

  local pr_id
  pr_id="$(open_pr)"
  echo "Using PR #$pr_id in $PROJECT_KEY/$REPO_SLUG"

  run_all_tests "$pr_id"
}

main "$@"
