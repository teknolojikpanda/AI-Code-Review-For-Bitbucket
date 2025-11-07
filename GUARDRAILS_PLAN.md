## AI Review Guardrails – Implementation Plan

### 1. Configurable Concurrency & Queueing
1. Add new config parameters (global + per-repository) for `maxConcurrentReviews`, `maxQueuedReviews`, and `maxParallelChunks`.
2. Introduce a cluster-aware scheduler service that enqueues review requests and only dispatches when slots are available (manual/merge-blocking runs get priority).
3. Persist queue metadata (in-memory + short-term AO table) so UI can show pending runs; add admin REST endpoints to inspect/flush the queue.

### 2. Per-Repo/Project Rate Limiting
1. Extend `AIReviewServiceImpl` entry points with a token-bucket limiter keyed by project/repo to prevent a single repository from saturating the cluster.
2. Surface “throttled” events in progress history so users understand delays.

### 3. Bounded Worker Pools & Async Execution
1. Implement `ReviewWorkerPool` and route `executeWithRun` through it (done).
2. Ensure comment/diff/merge-check work impersonates the acting user while on worker threads (done).

### 4. AI Backend Resilience
1. Wrap AI client calls with circuit breaker + retry/backoff policies; treat vendor 429/5xx separately. _(Existing `OllamaAiReviewClient` circuit breaker/rate limiter now tracked via plan)_
2. Cache overview responses keyed by commit hash to avoid duplicate processing on re-reviews. _(Completed via `OverviewCache`)_
3. Emit metrics for AI latency, error rates, retry counts, and circuit-breaker states (open/blocked/failure) by pushing structured snapshots through the shared `MetricsCollector`.
4. Return breaker + limiter telemetry to `AIReviewServiceImpl` so progress timelines, history views, and admin panels can surface when analysis is throttled or retried.

### 5. Admin Controls (Pause/Cancel)
1. Extend Progress REST resource with POST endpoints to pause/resume the scheduler and cancel individual queued/running reviews.
2. Update admin UI to list queued + running items with buttons for these actions.

### 6. Monitoring & Alerting
1. Add gauges/counters (Micrometer or existing MetricsCollector) for queue depth, active reviews, throttled requests, AI errors.
2. Expose a `/metrics` snapshot for external monitoring and document recommended alert thresholds.

### 7. History & Storage Hygiene
1. Implement configurable retention (e.g., keep last N days) and periodic cleanup job for `AIReviewHistory` plus chunk telemetry.
2. Compress large `progress`/`metrics` blobs before persisting to reduce AO load.

### 8. Testing & Rollout
1. Unit + integration tests covering scheduler limits, rate limiting, circuit breaker fallback, and admin controls.
2. Provide feature flags to enable guardrails gradually (cluster-wide toggle in config).
3. Update documentation: admin guide for new settings + troubleshooting playbook.

> **Next Steps:** Wire the new breaker/latency telemetry into Progress + `/metrics` endpoints, add regression coverage, and then proceed with the Admin Controls (pause/cancel) workstream.
