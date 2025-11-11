## AI Review Guardrails – Implementation Plan

### 1. Configurable Concurrency & Queueing
[X] 1. Add new config parameters (global + per-repository) for `maxConcurrentReviews`, `maxQueuedReviews`, and `maxParallelChunks`.
[X] 2. Introduce a cluster-aware scheduler service that enqueues review requests and only dispatches when slots are available (manual/merge-blocking runs get priority).
[X] 3. Persist queue metadata (in-memory + short-term AO table) so UI can show pending runs; add admin REST endpoints to inspect/flush the queue.
[X] 4. Implement fairness policies (per-project weight, round-robin buckets) plus backpressure rules so one repository cannot starve the rest. _(Per-repo/per-project queue caps now enforced; weighted dispatch/backpressure next)_
[X] 5. Stream queue state to the UI (progress drawer + admin page) via SSE/polling, including estimated start times and reason codes when items are deferred.
[X] 6. Add predictive ETAs by sampling historical review durations per repo/project so users get realistic wait times.
[X] 7. Provide bulk actions (pause/resume specific repositories, reorder queue items) for admins to triage incidents.
[X] 8. Emit queue depth, wait time percentiles, and fairness violations into `/metrics` + audit logs for long-term tuning.

### 2. Per-Repo/Project Rate Limiting
[X] 1. Extend `AIReviewServiceImpl` entry points with a token-bucket limiter keyed by project/repo to prevent a single repository from saturating the cluster.
[X] 2. Surface “throttled” events in progress history so users understand delays. _(Progress + history timelines now emit `review.throttled` with scope, retry ETA, and limiter metadata.)_
[X] 3. Share limiter counters cluster-wide using AO + caching (with short TTL invalidation) so scaling nodes respect the same budgets. _(AO buckets + cached snapshots back the new `GuardrailsRateLimitStore`.)_
[X] 4. Provide global + per-repo admin controls to temporarily raise/lower limits and display recent throttle incidents directly on the configuration screen. _(Overrides + incidents surfaced via `/rest/ai-reviewer/1.0/config/limiter` and Config UI payload.)_
[X] 5. Emit per-repo limiter metrics (recent throttle rate, refill ETA, tokens remaining) into progress snapshots and `/metrics` so teams can self-diagnose. _(Bucket states now include remaining tokens/reset ETA + metrics emit per-scope samples.)_
[X] 6. Add auto-snooze/resume logic that can temporarily relax limits for high-priority repos (merge-critical) while logging the override for auditability. _(Priority scope lists now drive AO-backed auto-snooze overrides + audit trails.)_
[X] 7. Introduce alert thresholds per repo/project so stakeholders get notified before throttling becomes blocking. _(Configurable percent floors + per-scope overrides now raise rate-limiter alerts before 100% consumption.)_
[X] 8. Allow “burst credits” that can be granted programmatically (e.g., via REST) for CI spikes, with automatic expiration and audit entries. _(New automation REST endpoints grant/revoke credits, backed by AO audit + runtime consumption support.)_

### 3. Bounded Worker Pools & Async Execution
[ ] 1. Implement `ReviewWorkerPool` and route `executeWithRun` through it (done).
[ ] 2. Ensure comment/diff/merge-check work impersonates the acting user while on worker threads (done).
[ ] 3. Track pool utilization/queue depth metrics and expose them via `/metrics` + progress history so saturation is visible.
[ ] 4. Add graceful degradation rules (e.g., temporarily cap `maxParallelChunks` when worker utilization > 90%) with operator overrides.
[ ] 5. Provide an admin dashboard card that lists current worker pool stats per node for quick diagnosis.
[ ] 6. Implement adaptive scaling hints (recommend adding nodes / enabling auto-scale) when sustained utilization stays high.
[ ] 7. Capture per-task execution timelines so we can identify slow operations (diff fetch, AI call, comment publish) within worker threads.

### 4. AI Backend Resilience
[ ] 1. Wrap AI client calls with circuit breaker + retry/backoff policies; treat vendor 429/5xx separately. _(Existing `OllamaAiReviewClient` circuit breaker/rate limiter now tracked via plan)_
[ ] 2. Cache overview responses keyed by commit hash to avoid duplicate processing on re-reviews. _(Completed via `OverviewCache`)_
[ ] 3. Emit metrics for AI latency, error rates, retry counts, and circuit-breaker states (open/blocked/failure) by pushing structured snapshots through the shared `MetricsCollector`.
[ ] 4. Return breaker + limiter telemetry to `AIReviewServiceImpl` so progress timelines, history views, and admin panels can surface when analysis is throttled or retried.
[ ] 5. Enrich `ProgressRegistry` updates (e.g., `analysis.started`, `analysis.completed`) with the latest circuit snapshot + current chunk identifiers so the PR progress panel shows exactly what is running and whether calls are throttled.
[ ] 6. Teach `AIReviewHistoryService` to persist that telemetry so the history UI can display past breaker or rate-limiter incidents alongside chunk timing.
[ ] 7. Add configurable retry policies per model (different backoff windows for overview vs chunk calls) with UI knobs for admins.
[ ] 8. Implement proactive health probes that periodically test models and mark them “degraded” before real reviews fail, falling back automatically.
[ ] 9. Surface cumulative AI vendor latency/error stats per endpoint so operators can justify switching models/providers if reliability drops.

### 5. Admin Controls (Pause/Cancel)
[ ] 1. Extend Progress REST resource with POST endpoints to pause/resume the scheduler and cancel individual queued/running reviews. _(Pause/resume/cancel endpoints implemented, plus AO-backed audit logging for every queue override)_
[ ] 2. Update admin UI to list queued + running items with buttons for these actions.
[ ] 3. Persist scheduler state (active, paused, draining) plus operator metadata in AO so the setting survives restarts and appears in progress timelines/audit logs.
[ ] 4. Add permission checks + audit events so only global admins can pause/cancel and every action is traceable (who, when, which PR/repository, reason). _(Backend now records actor + reason; UI surfacing + Bitbucket audit feed pending)_
[ ] 5. Provide bulk cancellation (per repo/project or entire queue) with confirmation prompts and progress feedback.
[ ] 6. Integrate pause/cancel actions with the fairness + rate-limit subsystems so overrides are reflected consistently across telemetry.
[ ] 7. Offer REST + CLI tooling so automation can pause/resume during maintenance windows.

### 6. Monitoring & Alerting
[ ] 1. Add gauges/counters (Micrometer or existing MetricsCollector) for queue depth, active reviews, throttled requests, AI errors.
[ ] 2. Expose a `/metrics` snapshot for external monitoring and document recommended alert thresholds.
[ ] 3. Include aggregated circuit-breaker + rate-limiter stats (open duration, blocked call deltas, retry counts) in the `/metrics` response so platform monitoring can alert on sustained degradation.
[ ] 4. Add lightweight REST/ADF panels inside Bitbucket admin that summarize these metrics for quick diagnosis without leaving the product.
[ ] 5. Provide outbound alert hooks (webhook/email/Atlassian Alerts) that trigger when breaker openness, queue depth, or throttle rate crosses operator-defined thresholds. _(Initial REST-based alert feed now available via `/rest/ai-reviewer/1.0/alerts`; next step is wiring this into external channels.)_
[ ] 6. Build a “Guardrails Health” dashboard that correlates queue, limiter, breaker, and worker pool metrics over time.
[ ] 7. Store alert history + operator acknowledgements so we can audit response times and improve playbooks.

### 7. History & Storage Hygiene
[ ] 1. Implement configurable retention (e.g., keep last N days) and periodic cleanup job for `AIReviewHistory` plus chunk telemetry. _(Automated job + AO-backed status + admin controls implemented.)_
[ ] 2. Compress large `progress`/`metrics` blobs before persisting to reduce AO load.
[ ] 3. Add a background AO maintenance task that runs during off-peak hours, batching deletes to avoid table locks and emitting metrics for processed rows.
[ ] 4. Surface retention/cleanup status in the admin UI (last run, duration, records purged) so operators can confirm hygiene jobs run successfully. _(Health dashboard now shows schedule controls plus recent cleanup runs.)_
[ ] 5. Provide export tooling (CSV/JSON) before cleanup runs so teams can archive long-lived data externally. _(Retention export REST + streaming download endpoints implemented.)_
[ ] 6. Introduce integrity checks that ensure orphaned progress/metrics records are detected and repaired automatically. _(Integrity reporting + auto-repair REST endpoint shipped.)_

### 8. Testing & Rollout
[ ] 1. Unit + integration tests covering scheduler limits, rate limiting, circuit breaker fallback, and admin controls.
[ ] 2. Provide feature flags to enable guardrails gradually (cluster-wide toggle in config).
[ ] 3. Update documentation: admin guide for new settings + troubleshooting playbook.
[ ] 4. Stage rollout via dark feature toggles per customer cohort, with telemetry hooks to validate performance before default-on.
[ ] 5. Create a regression checklist (manual + automated) to run before enabling guardrails cluster-wide.
[ ] 6. Publish operator runbooks + incident playbooks that reference the new telemetry panels/alerts so on-call engineers can triage failures quickly.
[ ] 7. Automate rollout/rollback scripts (e.g., Bitbucket REST or feature toggle CLI) so the guardrails bundle can be enabled or backed out safely during incidents. _(Automation REST endpoints + webhook channel manager implemented; UI shortcuts + scripting docs next.)_
[ ] 8. Run load/perf tests that simulate worst-case PR volumes to validate scheduler + limiter behavior before GA.
[ ] 9. Collect beta-customer feedback via in-product surveys and iterate on defaults before expanding rollout.

> **Next Steps:**
> 1. Wire limiter warning telemetry into outbound alert channels + CLI helpers (Sections 6.5/8.5) so ops teams can subscribe/automate responses.
> 2. Ship CLI/SDK utilities that wrap the burst-credit REST endpoints for CI systems (Section 8.5).
> 3. Add usage analytics for burst credits/alerts (Section 6.6) to tune defaults and catch abuse.
