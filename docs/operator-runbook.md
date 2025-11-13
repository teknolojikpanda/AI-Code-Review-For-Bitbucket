# AI Code Reviewer – Operator Runbook

This playbook explains how to monitor and operate the Guardrails features that gate AI review throughput. All endpoints listed below require Bitbucket *System Administrator* permissions.

## 1. Monitoring Endpoints

| Endpoint | Description |
| --- | --- |
| `GET /rest/ai-reviewer/1.0/monitoring/runtime` | Same payload that powers the Health Dashboard. Use for ad‑hoc inspection. |
| `GET /rest/ai-reviewer/1.0/metrics` | Machine-readable export that flattens queue, limiter, worker, and retention metrics. The response contains a `runtime` object (detailed snapshot) and a `metrics` array (scalar metric points). |
| `GET /rest/ai-reviewer/1.0/alerts` | Evaluates the current telemetry and returns synthesized guardrail alerts (severity, summary, recommendation). Use this to wire chat/email alerts without scraping the UI. |
| `GET /rest/ai-reviewer/1.0/config/scope` | Returns the active scope mode (`all` vs `repositories`) plus the currently whitelisted repositories so automation can inspect rollout status. |
| `POST /rest/ai-reviewer/1.0/config/scope` | Updates scope mode. Payload supports `{"mode":"all"}` for fleet-wide enablement or `{"mode":"repositories","repositories":[{"projectKey":"PRJ","repositorySlug":"repo"}]}` for explicit whitelists. |
| `GET /rest/ai-reviewer/1.0/history/cleanup/export` | Returns a JSON payload of retention candidates. Use `includeChunks=true` to inline chunk telemetry for auditing prior to deletion. |
| `GET /rest/ai-reviewer/1.0/history/cleanup/export/download` | Streams the same data as a JSON or CSV attachment (`format=csv`) so you can archive it with incident tickets. |
| `GET /rest/ai-reviewer/1.0/history/cleanup/integrity` | Runs integrity checks (chunk mismatch + corrupt progress/metrics detection) against the oldest review records. |
| `POST /rest/ai-reviewer/1.0/history/cleanup/integrity` | Same report as above, but with optional `repair=true` to automatically zero out corrupt JSON blobs and re-align chunk counts before cleanup resumes. |
| `GET /rest/ai-reviewer/1.0/automation/rollout/state` | Shows the current scheduler mode (ACTIVE, PAUSED, DRAINING), actor, timestamp, and reason. |
| `POST /rest/ai-reviewer/1.0/automation/rollout/{mode}` | Switches the scheduler mode (`active`, `pause`, `drain`) with an optional `reason` payload so rollout/rollback can be automated. |
| `GET /rest/ai-reviewer/1.0/automation/channels` | Lists outbound guardrails alert channels with pagination metadata (`limit`, `offset`, `total`). |
| `POST /rest/ai-reviewer/1.0/automation/channels` | Creates a webhook channel (HTTP/S). Payload accepts `signRequests` (default true), optional `secret`, and retry tuning (`maxRetries`, `retryBackoffSeconds`). |
| `PUT /rest/ai-reviewer/1.0/automation/channels/{id}` | Updates description/enablement and the security knobs above. Include `rotateSecret=true` to auto-generate a new signing secret. |
| `DELETE /rest/ai-reviewer/1.0/automation/channels/{id}` | Removes a channel (used when rotating credentials or off-boarding an incident room). |
| `POST /rest/ai-reviewer/1.0/automation/channels/{id}/test` | Sends a benign sample alert to the target URL so operators can verify wiring before relying on production alerts. |
| `GET /rest/ai-reviewer/1.0/automation/alerts/deliveries` | Lists recent webhook deliveries (success flag, HTTP status, payload snippet) so you can audit outages. |
| `POST /rest/ai-reviewer/1.0/automation/alerts/deliveries/{id}/ack` | Marks a delivery as acknowledged with an optional note—use this to capture incident response hand-offs. |

### Recommended Alert Thresholds

`GET /rest/ai-reviewer/1.0/metrics` returns an `alertThresholds` object alongside the flattened `metrics` array. Each entry lists recommended `warning`/`critical` values, the preferred comparison `direction`, and a short description so monitoring systems can be wired automatically. The defaults are summarised below:

| Metric | Warning | Critical | Direction | Rationale |
| --- | --- | --- | --- | --- |
| `ai.queue.availableSlots` | ≤ 1 slot | 0 slots | `lte` | All worker capacity is consumed; reviews will start queueing. |
| `ai.queue.waiting` | ≥ 5 reviews | ≥ 15 reviews | `gte` | Indicates backlog building; consider pausing low-priority repos. |
| `ai.queue.scheduler.paused` | 1 | 1 | `eq` | Scheduler paused outside maintenance windows. |
| `ai.worker.queuedTasks` | ≥ 10 tasks | ≥ 25 tasks | `gte` | Worker pool saturated; scale nodes or reduce chunk concurrency. |
| `ai.rateLimiter.totalThrottles` | ≥ 5 events/hr | ≥ 20 events/hr | `gte` | High throttle volume—raise limits or grant burst credits. |
| `ai.alerts.deliveries.failureRate` | ≥ 10% | ≥ 25% | `gte` | Webhook channel failing, alerts not leaving the cluster. |
| `ai.retention.cleanup.lastRunAgeSeconds` | ≥ 86,400s (24h) | ≥ 172,800s (48h) | `gte` | Cleanup job overdue; AO history will bloat. |
| `ai.retention.cleanup.lastErrorFlag` | 1 | 1 | `eq` | Last cleanup failed; inspect `/history/cleanup/log`. |
| `ai.model.errorRate` | ≥ 5% | ≥ 10% | `gte` | Vendor/model instability impacting review results. |
| `ai.alerts.pendingAcknowledgements` | ≥ 1 delivery | ≥ 5 deliveries | `gte` | Outstanding alerts that haven't been acknowledged. |
| `ai.alerts.pendingOldestSeconds` | ≥ 900s (15m) | ≥ 3,600s (60m) | `gte` | Oldest unacknowledged alert is getting stale. |
| `ai.alerts.ack.latencySecondsAvg` | ≥ 300s (5m) | ≥ 900s (15m) | `gte` | Average acknowledgement latency exceeding the runbook target. |
| `ai.breaker.openSampleRatio` | ≥ 10% | ≥ 25% | `gte` | Circuit breaker spending too much time OPEN; vendor likely degraded. |
| `ai.breaker.blockedCallsAvgPerSample` | ≥ 1 call/sample | ≥ 5 calls/sample | `gte` | Large portions of calls are being blocked by the breaker. |
| `ai.rateLimiter.repo.avgRetryAfterMs` | ≥ 15,000 ms | ≥ 30,000 ms | `gte` | Repository throttles enforcing long retry-after windows. |
| `ai.rateLimiter.project.avgRetryAfterMs` | ≥ 20,000 ms | ≥ 45,000 ms | `gte` | Project throttles enforcing long retry-after windows. |

Consumers should treat `direction="lte"` as “alert when value is less than or equal to threshold” and `direction="gte"` as “greater than or equal”. The JSON payload mirrors this table so automation can stay in sync even if we adjust defaults later. A clustered alerting job polls these metrics every minute and automatically pushes any warning/critical transitions to the configured webhook channels (and logs each delivery). You can also trigger an on-demand run with `./scripts/guardrails-cli.sh alerts` if you need to force a notification outside the regular cadence.

> **In-product view:** the Health dashboard now includes a **Metrics Summary** card deck along with a **Guardrails Health Timeline** that shows the latest queue actions, throttle incidents, worker snapshots, and alert deliveries (with ack state) so operators can correlate events without leaving Bitbucket.

### Webhook Security & Retries

- **Signing:** Enable “Sign requests” on a channel to append `X-Guardrails-Signed-At` and `X-Guardrails-Signature` headers (HMAC-SHA256). Share the secret displayed on the health page with the receiver. Rotate secrets regularly via the UI or `rotateSecret=true` on `PUT`.
- **Retry policy:** Configure `maxRetries` (0-5) plus `retryBackoffSeconds` (1-60). Guardrails retries failed deliveries with linear backoff (`backoff * attempt`). Keep values conservative to avoid overwhelming degraded systems.
- **Auditing / ACK:** Use the Alert Delivery History table (or `/automation/alerts/deliveries`) to track every attempt, then acknowledge entries once incidents are reviewed so the trail stays clean.
- **Auto-suppression:** If a channel fails repeatedly (3 strikes within ~10s), Guardrails auto-disables it and logs the action. Re-enable from the Health UI after the downstream system recovers.
| `GET /rest/ai-reviewer/1.0/automation/alerts/deliveries` | Paginates the most recent webhook deliveries (success/failure, HTTP code, payload snippet) for auditing. |
| `POST /rest/ai-reviewer/1.0/automation/alerts/deliveries/{id}/ack` | Marks a delivery as acknowledged (optionally storing a note) so the incident log reflects operator hand-offs. |

Core metric names emitted via `/metrics`:

* `ai.queue.*` – Active/waiting reviews, slot availability, per-scope queue pressure, scheduler paused flag.
* `ai.worker.*` – Worker pool utilisation (configured size, active threads, queued tasks).
* `ai.rateLimiter.*` – Current rate limiter budgets, tracked scopes, throttle counts, and retry-after windows (`repo|project.avgRetryAfterMs`, `lastThrottleAgeSeconds`).
* `ai.breaker.*` – Aggregated circuit-breaker stats (open sample ratio, blocked call counts, client-side hard failures).
* `ai.retention.*` – History retention window, backlog older than the window, cleanup schedule state (interval, batch size, enabled flag), last run duration/error state.
* `ai.review.duration.*` – Number of samples available for ETA calculations.

Every response is timestamped via `generatedAt` so automation can detect stale data.

## 2. CLI Helper

For maintenance windows it is often easier to script scheduler changes. The repository ships a tiny wrapper at `scripts/guardrails-cli.sh` that issues the rollout REST calls with curl. Usage:

```bash
export GUARDRAILS_BASE_URL=https://bitbucket.example.com
export GUARDRAILS_AUTH=admin:api-token

# Pause immediately with a reason
./scripts/guardrails-cli.sh pause "Nightly patching"

# Drain (finish current runs, block new ones)
./scripts/guardrails-cli.sh drain "Deploy in progress"

# Resume normal scheduling
./scripts/guardrails-cli.sh resume

# Inspect current state
./scripts/guardrails-cli.sh state

# Manually evaluate + stream alerts (also notifies webhooks)
./scripts/guardrails-cli.sh alerts

# Inspect cleanup schedule + recent runs
./scripts/guardrails-cli.sh cleanup-status

# Trigger an on-demand cleanup using the saved schedule
./scripts/guardrails-cli.sh cleanup-run

# Export retention candidates before a manual cleanup (JSON preview or CSV download)
./scripts/guardrails-cli.sh cleanup-export --preview --days=120 --limit=50
./scripts/guardrails-cli.sh cleanup-export --format=csv --days=180 --limit=500 --chunks --output=/tmp/ai-history-archive.csv

# Sample or repair retention integrity
./scripts/guardrails-cli.sh cleanup-integrity --days=90 --sample=150
./scripts/guardrails-cli.sh cleanup-integrity --days=90 --sample=150 --repair

# Toggle review scope (all repos vs allow-list)
./scripts/guardrails-cli.sh scope --mode=all
./scripts/guardrails-cli.sh scope --mode=repositories --repo PRJ/repo-one --repo PRJ/repo-two
```

The script prints the scheduler JSON response (requires `jq` for pretty output). Because it only depends on curl it can be embedded in Cron, Rundeck, or any CI/CD workflow to automate pause/resume sequences.

### Alert History & Acknowledgements

- `GET /rest/ai-reviewer/1.0/automation/alerts/deliveries` now returns every webhook delivery plus an `ackStats` block summarising pending count, oldest pending age, and the average acknowledgement latency. Use this feed to prove that incidents were acknowledged in time.
- `POST /rest/ai-reviewer/1.0/automation/alerts/deliveries/{id}/ack` stores the actor, timestamp, and optional note directly in the AO table so you can audit response hand-offs later. The Health metrics `ai.alerts.pendingAcknowledgements`, `ai.alerts.pendingOldestSeconds`, and `ai.alerts.ack.latencySecondsAvg` track the same data for dashboards.
- Infrastructure teams should keep the pending count at zero during business hours; if a webhook fails repeatedly, the auto-disable mechanism will trip and you will see the pending metrics climb until a human re-enables the channel and acknowledges the alert.

### Worker Pool Degradation

- The admin config page now exposes **Worker Pool Degradation** (enabled by default). When utilization stays above ~90% or the worker queue keeps growing, Guardrails halves the effective `parallelThreads` (and eventually drops to one) so nodes can recover without manual intervention.
- Disable the toggle if you are performing controlled perf tests or want the previous “always parallel” behaviour. Re-enabling immediately reapplies the adaptive cap during the next review.
- Each degraded run emits a `config.workerDegradation` progress event plus the `config.workerDegradationActive` gauge inside `/metrics`, so dashboards and alerts can surface when the safety net is taking effect.
- The Health dashboard’s **Scaling Hints** card evaluates queue backlog + worker utilization to recommend when to add nodes, enable auto-scale, or relax `maxConcurrentReviews`. Treat a persistent hint as a pre-incident signal before saturation trips SLAs.

### Execution Timeline

- Every review run now captures per-task timings (diff collection, chunk planning, each AI chunk, comment publishing). These appear inside the PR progress panel & history timelines with `durationMs`, thread name, and outcome so you can spot slow stages without diving into logs.
- The same samples ship in `metrics.timeline.events`, making it easy to push slowest operations into external APM/BI tools.

## 2. Queue Saturation Playbook

1. **Check `/metrics` for queue saturation:** `ai.queue.availableSlots` = 0 or `ai.queue.waiting` steadily increasing indicates saturation.
2. **Inspect scope pressure:** Large `ai.queue.repoPressureScopes` or `ai.queue.projectPressureScopes` means per-scope caps are engaged.
3. **Actions:**
   - Short-term: Use the Health Dashboard’s admin controls to pause low-priority repos or cancel queued runs stuck behind critical work.
   - Structural: Adjust per-project or per-repo queue caps in the Guardrails configuration so critical repos get more slots.
4. **Verification:** `/metrics` should show waiting counts dropping and available slots recovering. Document the action in your incident tracker.

## 3. Retention Cleanup Playbook

1. **Watch retention backlog:** `ai.retention.entriesOlderThanWindow` shows how many history rows exceed the retention window.
2. **Validate scheduler health:** `ai.retention.cleanup.enabled` (should be 1) and `ai.retention.cleanup.lastErrorFlag` (should be 0). A high `ai.retention.cleanup.lastRunAgeSeconds` means the job has not run recently. Use the “Recent Cleanup Runs” table on the Health dashboard to inspect the last few executions (duration, actor, manual/system, error text).
   - The Health page Runtime Snapshot now includes a **Cleanup** card that mirrors these values (enabled flag, last run timestamp, duration, deleted histories/chunks, batches, and the configured window) so you can confirm hygiene at a glance without drilling into the Operations page.
3. **Pre-cleanup export/integrity:**
   - Use the **Retention Export** form on the Operations page to pull a JSON preview or download a CSV/JSON snapshot (retention window, row limit, optional chunk telemetry) before purging data. This leverages the `/history/cleanup/export` + `/history/cleanup/export/download` endpoints so you do not need curl scripts.
   - `GET /history/cleanup/export` for a quick JSON snapshot, or `/history/cleanup/export/download?format=csv&includeChunks=true` to capture a spreadsheet-friendly attachment with per-chunk telemetry.
   - Run the **Integrity Check & Repair** panel (or `cleanup-integrity` CLI command) to detect orphaned progress/metrics blobs before deleting anything. The UI/CLI both call `GET /history/cleanup/integrity` for sampling and `POST /history/cleanup/integrity` with `{"repair": true}` when you are ready to clear corrupt rows automatically.
   > **Heads-up:** Large `metricsJson` / `progressJson` blobs are automatically stored as gzip+Base64 payloads (`gz:` prefix). All REST/CLI exports already decompress them, but if you query the AO tables directly you will need to decode the field before inspection.
   - Configure the **Maintenance Window** card (start hour, duration, max batches) so the cleanup runner only touches AO tables during off-peak hours. By default it starts at 02:00 (cluster local time), runs for 180 minutes, and executes up to six 200-row batches—tune these knobs if your instance needs a shorter or longer digestion period.
4. **Actions:**
   - Trigger a manual cleanup from the Health Dashboard (Run Once) if backlog keeps growing.
   - Adjust `ai.retention.cleanup.intervalMinutes` and `batchSize` to keep up with growth.
5. **Verification:** After a run, `/metrics` should show fresh `lastRunAgeSeconds` near zero and `lastDeletedHistories`/`lastDeletedChunks` matching the deleted batch counts. Capture the run + export artefact in ops notes.

## 4. Rate Limiter / Worker Trouble

* **Rate limiter:** High values for `ai.rateLimiter.trackedRepoBuckets` with `repoLimitPerHour` throttling legitimate work => raise limits temporarily and watch `/metrics` to confirm automation catches up.
* **Worker saturation:** When `ai.worker.activeThreads` equals `ai.worker.configuredSize` and `ai.worker.queuedTasks` keeps climbing, consider scaling the node pool or lowering `maxParallelChunks`.
- Every throttled review now records a `limiterSnapshot` (consumed tokens, limit, ETA until reset) inside PR progress/history metrics so you can see exactly why Guardrails blocked the run and whether tokens are about to refill.
- The History admin screen now surfaces a “Guardrails Telemetry” card per run, showing the captured rate-limiter snapshots plus the breaker state transitions so operators can audit when a repo was throttled or when the circuit tripped without digging through raw JSON.
- Proactive model probes run every minute and will mark the primary model `degraded` after repeated failures. When this happens, Guardrails automatically skips straight to the fallback model so production reviews keep flowing and you can investigate the vendor outage with the health telemetry panel.
- Guardrails telemetry now exposes `modelStats.entries`—aggregated latency/failure metrics per `endpoint+model` derived from recent chunk invocations. Use this to compare success rates and P95 latency between vendors before flipping the primary model.
- Retry knobs now distinguish overview vs chunk AI calls: overview defaults to 2 attempts with a 1500 ms backoff (to protect expensive prompts), while chunk processing keeps the original 3 attempts/1000 ms cadence. Use the admin UI’s Retry Configuration section to tune them independently.

### Limiter snapshot, overrides, and incidents

* `GET /rest/ai-reviewer/1.0/config/limiter` returns a single payload with:
  * `snapshot`: Current repo/project budgets, remaining tokens, reset ETAs, and recent throttle counts per hot scope.
  * `overrides`: Active manual overrides (scope, identifier, limit, expiry, actor, reason).
  * `incidents`: Recent throttle events with scope, repo/project coordinates, retry-after, and reason.
* **Granting a temporary override:** `POST /rest/ai-reviewer/1.0/config/limiter/overrides`

```json
{
  "scope": "repository",           // repository | project | global
  "identifier": "payment-service", // omit for global
  "limitPerHour": 30,
  "durationMinutes": 90,           // optional; falls back to indefinite when omitted
  "reason": "High-priority hotfix"
}
```

  * Overrides are persisted cluster-wide (AO) and respected immediately thanks to the persistent limiter buckets.
  * `durationMinutes` is clamped to 7 days; alternatively supply an absolute `expiresAt` epoch millis.
  * Every override stores the Bitbucket user key/display name so you can audit who relaxed limits.
* **Revoking overrides:** `DELETE /rest/ai-reviewer/1.0/config/limiter/overrides/{id}` removes the entry (use the `overrides` list to discover IDs). 404 is returned if the override already expired/was deleted.
* **Investigating throttles:** The `incidents` list (also exposed via the Config payload) shows the last 40 throttle events. Pair these with `/metrics` (`ai.rateLimiter.repo|project.*`) to decide whether to grant burst credits or adjust defaults.
* **Auto-snooze:** Populate `priorityProjects` (comma-separated project keys) and/or `priorityRepositories` (`PROJECT/repo`) via `/rest/ai-reviewer/1.0/config`. Listed scopes automatically receive temporary overrides using `priorityRateLimitSnoozeMinutes`, `priorityRepoRateLimitPerHour`, and `priorityProjectRateLimitPerHour`. Each burst is persisted as an override (actor `Guardrails Auto-Snooze`) so operators can audit when high-priority repos were given extra throughput.
* **Alert thresholds:** `repoRateLimitAlertPercent` and `projectRateLimitAlertPercent` define the global percentage (of hourly budget consumed) that triggers a warning before hard throttling kicks in. Use `repoRateLimitAlertOverrides` or `projectRateLimitAlertOverrides` to specify custom percentages (e.g., `core-service=70,shared-lib=60`). Alerts list the scope, usage, and reset ETA so responders can preemptively grant burst credits or raise limits.
* **Burst credits:** When CI spikes need more throughput, call `POST /rest/ai-reviewer/1.0/automation/burst-credits` with `{ "scope": "repository", "projectKey": "PRJ", "repositorySlug": "service-api", "tokens": 10, "durationMinutes": 45, "reason": "CI hotfix" }`. This grants 10 extra tokens for the next 45 minutes. List or revoke credits via `GET/DELETE /rest/ai-reviewer/1.0/automation/burst-credits`. Every grant/consume action is persisted so you can audit which team borrowed capacity.

## 5. Alert Integration Checklist

1. Point your monitoring system at `/rest/ai-reviewer/1.0/metrics` (for raw metrics) or `/rest/ai-reviewer/1.0/alerts` (for synthesized alerts).
2. Add alert rules for:
   - `ai.queue.availableSlots == 0` for sustained periods.
   - `ai.retention.cleanup.lastErrorFlag == 1`.
   - `ai.retention.cleanup.lastRunAgeSeconds` exceeding 24h.
3. Reference this runbook in alert descriptions so on-call engineers can execute the appropriate play immediately.

Keeping this document up to date is part of the Guardrails plan (section 8.5). Update it whenever new controls or telemetry fields ship.

## Feature Rollout Cohorts

Use rollout cohorts to stage guardrails for specific projects or repositories before enabling them cluster-wide.

### UI Workflow

1. Navigate to **Administration → AI Code Reviewer → Operations** and scroll to **Rollout Cohorts**.
2. Review the table to see each cohort’s scope, dark feature key, rollout percentage, and enforcement metrics (enforced/shadow/fallback/completed counts).
3. Click **Add Cohort** (or **Edit**) to define:
   - Key + display name/description.
   - Scope (`Global`, `Project`, or `Repository`). Project/repository fields are enabled automatically based on scope.
   - Rollout percentage (0‑100) if you want to sample only a portion of matching reviews.
   - Optional SAL dark feature key that must be enabled before enforcement happens.
   - Enabled toggle. When disabled the cohort collects telemetry but runs in shadow mode.
4. Save the cohort. As soon as at least one cohort exists, any repository that does not match an enabled cohort runs guardrails in fallback/shadow mode; the header shows the current default.

### REST API

| Method | Endpoint | Description |
| --- | --- | --- |
| `GET` | `/rest/ai-reviewer/1.0/automation/rollout/cohorts` | Returns every cohort plus telemetry (startedEnforced, startedShadow, startedFallback, completed) and the `defaultMode`. |
| `POST` | `/rest/ai-reviewer/1.0/automation/rollout/cohorts` | Creates a cohort. Body mirrors the UI fields (`key`, `displayName`, `scopeMode`, `projectKey`, `repositorySlug`, `rolloutPercent`, `darkFeatureKey`, `enabled`). |
| `PUT` | `/rest/ai-reviewer/1.0/automation/rollout/cohorts/{id}` | Updates the cohort with the supplied `id`. |
| `DELETE` | `/rest/ai-reviewer/1.0/automation/rollout/cohorts/{id}` | Removes the cohort. Remaining repositories immediately fall back to the default rollout mode. |

All endpoints require Bitbucket system-administrator permissions and return `400` with a descriptive error when validation fails (duplicate keys, missing scope fields, etc.).

### Telemetry

`GET /rest/ai-reviewer/1.0/monitoring/runtime` now includes a `rollout` object:

```json
"rollout": {
  "defaultMode": "shadow",
  "cohorts": [
    {
      "key": "pilot",
      "scopeMode": "repository",
      "projectKey": "PRJ",
      "repositorySlug": "app",
      "enabled": true,
      "rolloutPercent": 100,
      "darkFeatureKey": "com.example.guardrails.pilot",
      "metrics": {
        "startedEnforced": 12,
        "startedShadow": 3,
        "startedFallback": 0,
        "completed": 10,
        "lastEvaluationAt": 1731419784000
      }
    }
  ]
}
```

Use these counters to validate performance before promoting guardrails to all repositories. The Operations UI renders the same information and the CLI/automation endpoints can ingest the telemetry for custom dashboards.

## Burst Credit Automation (CLI)

The `scripts/guardrails-cli.sh` helper now exposes burst-credit operations so CI systems can request temporary guardrail capacity without crafting REST calls manually.

### Listing Credits

```bash
GUARDRAILS_BASE_URL=https://bitbucket.example.com \
GUARDRAILS_AUTH=admin:token \
  ./scripts/guardrails-cli.sh burst-list --include-expired
```

`--include-expired` is optional; by default only active credits are returned. Output is JSON (pretty-printed when `jq` is available).

### Granting Credits

```bash
./scripts/guardrails-cli.sh burst-grant \
  --scope repository \
  --repo PRJ/service-api \
  --tokens 10 \
  --duration 120 \
  --reason "Nightly load test" \
  --note "Triggered from Jenkins job #581"
```

- `--scope repository|project` (default repository)
- `--repo PROJECT/slug` for repository scope
- `--project KEY` for project scope
- `--tokens` extra executions (default 5)
- `--duration` validity in minutes (default 60)
- `--reason`/`--note` stored with the record for auditing

### Revoking Credits

```bash
./scripts/guardrails-cli.sh burst-revoke 42 --note "Cancelled canary"
```

The revoke command accepts the credit id (as reported by `burst-list`) and an optional note. All commands honour the same `GUARDRAILS_BASE_URL`/`GUARDRAILS_AUTH` environment variables used by the other CLI features.

## Regression Checklist

Before turning guardrails on for the full cluster (or after shipping a sizable change), follow the [Regression Checklist](regression-checklist.md). It captures:

- Automated suites to run (`mvn -q -DskipITs test`, packaging).
- Manual UI validation across queue/rollout/alerting/retention panels.
- CLI smoke tests (scope, scheduler, burst credits).
- Telemetry/alert sanity checks and worker/rate-limiter smoke tests.

Keep evidence (logs/screenshots) with the rollout ticket for auditability.

## Rollout Automation Script

`scripts/guardrails-rollout.sh` wraps the core CLI commands so SREs can flip guardrails on/off quickly during incidents:

### Enable Example

```bash
GUARDRAILS_BASE_URL=https://bitbucket.example.com \
GUARDRAILS_AUTH=admin:token \
  ./scripts/guardrails-rollout.sh enable \
    --scope repositories \
    --repo PRJ/service-api \
    --repo PRJ/mobile-app \
    --reason "Pilot cohort rollout"
```

- `--scope all|repositories` defaults to `all`.
- Repeat `--repo PROJECT/slug` for allow-listed repos when scope=`repositories`.
- `--reason` is persisted in the scheduler audit log.

### Disable Example

```bash
./scripts/guardrails-rollout.sh disable --drain --reason "Rollback during incident INC-1234"
```

- `--drain` drains in-flight reviews before pausing; omit to pause immediately.
- Script always prints the after-state so change management tickets can capture the outcome.

The script simply shells out to `guardrails-cli.sh`, so the same environment variables (`GUARDRAILS_BASE_URL`/`GUARDRAILS_AUTH`) apply.

## Telemetry Panels

### Health Dashboard (`/plugins/servlet/ai-reviewer/health`)
- **Queue & Worker cards**: monitor `active`, `waiting`, worker utilization, and any fairness violations. If the queue plateaus, confirm whether the scheduler is paused or degraded.
- **Model Health & Scaling Advisor**: shows primary vs fallback status plus recommended actions (add nodes, lower chunk size, etc.).
- **Cleanup & History cards**: last run timestamps, deleted rows, integrity errors. A “Failed” badge means run `guardrails-cli.sh cleanup-status` before the next retention window.

### Operations Dashboard (`/plugins/servlet/ai-reviewer/operations`)
- **Review Queue**: live list of queued/running runs with bulk cancel buttons, estimated wait, and rollout badges (enforced/shadow/fallback).
- **Rollout Controls & Cohorts**: current scheduler mode, default rollout, and per-cohort metrics (enforced/shadow/fallback counts). Use these during staged rollouts or incidents.
- **Alert Channels & Deliveries**: webhook status, retry counters, and acknowledgement workflow for incident response.
- **Automation Panels**: cleanup schedule, retention exports, integrity check/repair, and alert delivery history.

### Metrics/Alerts API Quick Checks
- `curl .../metrics | jq '.runtime.queue, .runtime.rateLimiter'` whenever the UI seems stale.
- `curl .../alerts` supplies synthesized advisories suitable for paging/on-call routing.

## Incident Playbooks

### Queue Saturation / Backlog Growth
1. Check Operations → Review Queue (`waiting`, `scope pressure`, `bulk-cancel` availability).
2. Verify scheduler mode (Rollout Controls). If unintended pause/drain, resume with justification.
3. Inspect telemetry via `/metrics` to confirm worker utilization (`ai.worker.queuedTasks`) vs queue depth.
4. Optional mitigations:
   - Drain low-priority repos using bulk cancel (`queue-bulk-btn` or CLI pause scope).
   - Grant burst credits (`guardrails-cli.sh burst-grant --repo PRJ/repo --tokens 10 --duration 30`) if the backlog is due to throttling.
5. Attach screenshots/CLI output to the incident ticket and update stakeholders.

### Rate-Limiter Throttling Storm
1. Look at Health Dashboard → Rate Limiter card (`recent throttles`, per-scope wait).
2. Use `guardrails-cli.sh alerts` to confirm whether limiter alerts fired.
3. If a handful of repos are impacted, grant temporary burst credits; for broader incidents, increase project limits via config or auto-snooze.
4. After the window ends, run `burst-list --include-expired` to verify credits consumed and revoke any stragglers.

### Alert Delivery Failures
1. Operations → Alert Deliveries table: filter for failure rate > warning threshold.
2. Re-test the channel (`channel-test` button or REST POST) and check outbound firewall/webhook host.
3. Rotate signing secrets (UI toggle) if the failure is due to auth changes.
4. Acknowledge the failing deliveries with context (`delivery-ack`) so audit logs reflect who handled the incident.

### Retention/Integrity Errors
1. Health Dashboard cleanup card shows `lastErrorFlag=1`. Fetch detailed status via `guardrails-cli.sh cleanup-status`.
2. Run `cleanup-integrity --repair` to fix orphaned metrics if the error references corrupt history rows.
3. If AO jobs keep failing, pause cleanup and file an ActiveObjects ticket with log excerpts.
