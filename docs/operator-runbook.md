# AI Code Reviewer – Operator Runbook

This playbook explains how to monitor and operate the Guardrails features that gate AI review throughput. All endpoints listed below require Bitbucket *System Administrator* permissions.

## 1. Monitoring Endpoints

| Endpoint | Description |
| --- | --- |
| `GET /rest/ai-reviewer/1.0/monitoring/runtime` | Same payload that powers the Health Dashboard. Use for ad‑hoc inspection. |
| `GET /rest/ai-reviewer/1.0/metrics` | Machine-readable export that flattens queue, limiter, worker, and retention metrics. The response contains a `runtime` object (detailed snapshot) and a `metrics` array (scalar metric points). |
| `GET /rest/ai-reviewer/1.0/alerts` | Evaluates the current telemetry and returns synthesized guardrail alerts (severity, summary, recommendation). Use this to wire chat/email alerts without scraping the UI. |
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
* `ai.rateLimiter.*` – Current rate limiter budgets and tracked scopes.
* `ai.retention.*` – History retention window, backlog older than the window, cleanup schedule state (interval, batch size, enabled flag), last run duration/error state.
* `ai.review.duration.*` – Number of samples available for ETA calculations.

Every response is timestamped via `generatedAt` so automation can detect stale data.

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
3. **Pre-cleanup export/integrity:**
   - `GET /history/cleanup/export` for a quick JSON snapshot, or `/history/cleanup/export/download?format=csv&includeChunks=true` to capture a spreadsheet-friendly attachment with per-chunk telemetry.
   - `GET /history/cleanup/integrity` to ensure chunk counts match what will be deleted. If the report flags mismatches, pause cleanup and run `POST /history/cleanup/integrity` with `{"repair": true}` to clear corrupt progress/metrics blobs and resync chunk counts automatically.
4. **Actions:**
   - Trigger a manual cleanup from the Health Dashboard (Run Once) if backlog keeps growing.
   - Adjust `ai.retention.cleanup.intervalMinutes` and `batchSize` to keep up with growth.
5. **Verification:** After a run, `/metrics` should show fresh `lastRunAgeSeconds` near zero and `lastDeletedHistories`/`lastDeletedChunks` matching the deleted batch counts. Capture the run + export artefact in ops notes.

## 4. Rate Limiter / Worker Trouble

* **Rate limiter:** High values for `ai.rateLimiter.trackedRepoBuckets` with `repoLimitPerHour` throttling legitimate work => raise limits temporarily and watch `/metrics` to confirm automation catches up.
* **Worker saturation:** When `ai.worker.activeThreads` equals `ai.worker.configuredSize` and `ai.worker.queuedTasks` keeps climbing, consider scaling the node pool or lowering `maxParallelChunks`.

## 5. Alert Integration Checklist

1. Point your monitoring system at `/rest/ai-reviewer/1.0/metrics` (for raw metrics) or `/rest/ai-reviewer/1.0/alerts` (for synthesized alerts).
2. Add alert rules for:
   - `ai.queue.availableSlots == 0` for sustained periods.
   - `ai.retention.cleanup.lastErrorFlag == 1`.
   - `ai.retention.cleanup.lastRunAgeSeconds` exceeding 24h.
3. Reference this runbook in alert descriptions so on-call engineers can execute the appropriate play immediately.

Keeping this document up to date is part of the Guardrails plan (section 8.5). Update it whenever new controls or telemetry fields ship.
