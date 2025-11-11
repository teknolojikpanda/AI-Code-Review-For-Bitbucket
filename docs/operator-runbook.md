# AI Code Reviewer – Operator Runbook

This playbook explains how to monitor and operate the Guardrails features that gate AI review throughput. All endpoints listed below require Bitbucket *System Administrator* permissions.

## 1. Monitoring Endpoints

| Endpoint | Description |
| --- | --- |
| `GET /rest/ai-reviewer/1.0/monitoring/runtime` | Same payload that powers the Health Dashboard. Use for ad‑hoc inspection. |
| `GET /rest/ai-reviewer/1.0/metrics` | Machine-readable export that flattens queue, limiter, worker, and retention metrics. The response contains a `runtime` object (detailed snapshot) and a `metrics` array (scalar metric points). |

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
2. **Validate scheduler health:** `ai.retention.cleanup.enabled` (should be 1) and `ai.retention.cleanup.lastErrorFlag` (should be 0). A high `ai.retention.cleanup.lastRunAgeSeconds` means the job has not run recently.
3. **Actions:**
   - Trigger a manual cleanup from the Health Dashboard (Run Once) if backlog keeps growing.
   - Adjust `ai.retention.cleanup.intervalMinutes` and `batchSize` to keep up with growth.
4. **Verification:** After a run, `/metrics` should show fresh `lastRunAgeSeconds` near zero and `lastDeletedHistories`/`lastDeletedChunks` matching the deleted batch counts. Capture the run result in ops notes.

## 4. Rate Limiter / Worker Trouble

* **Rate limiter:** High values for `ai.rateLimiter.trackedRepoBuckets` with `repoLimitPerHour` throttling legitimate work => raise limits temporarily and watch `/metrics` to confirm automation catches up.
* **Worker saturation:** When `ai.worker.activeThreads` equals `ai.worker.configuredSize` and `ai.worker.queuedTasks` keeps climbing, consider scaling the node pool or lowering `maxParallelChunks`.

## 5. Alert Integration Checklist

1. Point your monitoring system at `/rest/ai-reviewer/1.0/metrics`.
2. Add alert rules for:
   - `ai.queue.availableSlots == 0` for sustained periods.
   - `ai.retention.cleanup.lastErrorFlag == 1`.
   - `ai.retention.cleanup.lastRunAgeSeconds` exceeding 24h.
3. Reference this runbook in alert descriptions so on-call engineers can execute the appropriate play immediately.

Keeping this document up to date is part of the Guardrails plan (section 8.5). Update it whenever new controls or telemetry fields ship.
