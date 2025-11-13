# Guardrails Regression Checklist

Use this checklist before enabling the Guardrails bundle cluster-wide, after major upgrades, or whenever a hotfix touches scheduling/limiter codepaths.

## Automated Test Suite

1. `mvn -q -DskipITs test` – ensures unit/integration suites cover scheduler, limiter, worker pool, alerting, and automation REST layers.
2. `atlas-package` (or Bitbucket SDK equivalent) – validates the plugin packages/builds cleanly.
3. Optional soak: run `mvn -q -pl :ai-code-reviewer failsafe:integration-test` if ITs are enabled in the build farm.

## Manual Verification

### Admin & Operations UI
- **Operations → Review Queue**: confirm queued/running tables render, bulk cancel buttons work on a disposable PR.
- **Operations → Rollout Controls**: pause, drain, and resume, ensuring scheduler audit entries appear.
- **Operations → Rollout Cohorts**: create/edit/delete a dummy cohort; verify telemetry counters increment and default mode updates.
- **Operations → Alert Channels & Deliveries**: add a temp webhook (use requestbin) and send a test alert.
- **Operations → Alert Deliveries**: acknowledge a sample delivery; confirm audit trail updates.
- **Operations → Retention panels**: run “Cleanup Now” and “Integrity Check” in dry-run mode to confirm AO hooks remain healthy.

### CLI / Automation
- `guardrails-cli.sh pause/drain/resume` with a reason string.
- `guardrails-cli.sh scope --list` followed by a no-op update payload.
- `guardrails-cli.sh burst-list|burst-grant|burst-revoke` against a test repo to ensure the new automation helpers work end-to-end.

### Guardrail Telemetry
- `curl -u admin:token https://<base>/rest/ai-reviewer/1.0/monitoring/runtime | jq '.rollout,.queue,.rateLimiter'` to confirm snapshot contains expected sections (including rollout + limiter stats).
- `/rest/ai-reviewer/1.0/alerts` returns a payload (even empty) without HTTP errors.

### Worker & Rate Limiter Smoke Test
- Trigger a small PR review (manual run) and verify:
  - Review enters queue → running → completes.
  - Progress drawer shows `review.started`/`review.completed`.
- Simulate rate-limiter consumption (e.g., two rapid manual runs on same repo) and confirm throttled event surfaces in progress history.

## Sign-off

- Capture CLI + UI screenshots/logs in the change ticket.
- Ensure fallback plan documented (disable guardrails via Scope `mode=all` or feature toggles).
- Communicate rollout status on the relevant ops channel once the checklist passes.
