# Guardrails Contributor Guide

This guide helps new developers get productive quickly. It covers environment setup, coding standards, testing commands, and tips for common workflows.

## 1. Prerequisites

- Java 17 (matches Bitbucket 8.x baseline). Set `JAVA_HOME` accordingly.
- Maven 3.8+ (`mvn -v` should work).
- Node.js is **not** required; admin UI JS is plain ES5 served from Velocity templates.
- Atlassian SDK (optional) if you want to run `atlas-package` or integration tests against Bitbucket.

## 2. Repo Layout

```
src/main/java/com/teknolojikpanda/bitbucket/aireviewer   # REST + services + AO entities
src/main/java/com/teknolojikpanda/bitbucket/aicode       # AI orchestration (chunk planner, orchestrator, client)
src/main/resources/js                                    # Admin/Ops dashboard logic (jQuery + AUI)
src/main/resources/templates                             # Velocity templates
scripts/                                                 # CLI utilities (guardrails-cli, rollout helper)
docs/                                                    # All documentation (architecture, runbooks, perf, beta feedback)
```

## 3. Build & Test

| Command | Description |
| --- | --- |
| `mvn -q -DskipITs test` | Default unit + integration tests (skips heavy ITs). |
| `mvn -q verify` | Full suite (slower, runs Surefire + Failsafe if configured). |
| `atlas-package` | Optional, requires Atlassian SDK; builds the plugin JAR. |
| `python3 perf/load-test.py --help` | Verify the perf harness works (requires `requests`). |

Before pushing, run at least `mvn -q -DskipITs test`. All new features should include tests near the impacted package (e.g., `src/test/java/...` mirrors `src/main/java/...`).

## 4. Coding Standards

- **Services vs Core**: Keep Bitbucket-specific logic under `com.teknolojikpanda.bitbucket.aireviewer` and AI-agnostic logic under `com.teknolojikpanda.bitbucket.aicode`.
- **REST APIs**: Always enforce `requireSystemAdmin` (see `AutomationResource`) for admin endpoints.
- **AO Entities**: Keep column names under 30 chars (ActiveObjects restriction). Use short field names where necessary.
- **Telemetry**: When adding new metrics, update both `GuardrailsTelemetryService` and the UI/CLI that consumes the payload.
- **Logging**: Use `log.info` for admin-facing events (queue actions, state changes) and `log.debug` for noisy diagnostics. Avoid printing secrets/tokens.

## 5. Development Workflow

1. Create a feature branch off `master`.
2. Make changes + add tests.
3. Update docs under `docs/` if the feature introduces new behaviour (architecture, contributor, user guide, operations).
4. Run tests (`mvn -q -DskipITs test`).
5. Commit with a descriptive message (existing convention: `Plan X.Y: ...` for plan-related work).

## 6. Useful Scripts

- `scripts/guardrails-cli.sh` – Manage scheduler state, queue, cleanup, scope, rate limits, burst credits.
- `scripts/guardrails-rollout.sh` – Sequence scope changes + pause/resume actions.
- `perf/load-test.py` – Exercise monitoring endpoints while toggling scheduler state.

## 7. Debugging Tips

- **Queue issues**: Hit `/rest/ai-reviewer/1.0/progress/admin/queue` directly to inspect raw JSON (includes file-filter breakdown).
- **Rate limiter**: `/rest/ai-reviewer/1.0/monitoring/runtime` → `rateLimiter` block shows buckets, overrides, burst credits.
- **Worker pool**: Enable DEBUG for `ReviewWorkerPool` to see thread dispatch behaviour.
- **Telemetry**: `GuardrailsTelemetryServiceTest` demonstrates how to mock dependencies and assert snapshot contents.

## 8. Submitting PRs

Include:
- Summary of the change and affected plan item (if applicable).
- Screenshots/gifs for UI tweaks.
- Test output (paste `mvn -q -DskipITs test` tail).
- Links to updated docs (README, user guide, runbook, etc.).

Keeping this guide current makes onboarding future contributors dramatically easier—feel free to extend it with new lessons learned.
