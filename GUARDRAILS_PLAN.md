## AI Review Guardrails – Implementation Plan

### 1. Configurable Concurrency & Queueing
1. Add new config parameters (global + per-repository) for `maxConcurrentReviews`, `maxQueuedReviews`, and `maxParallelChunks`.
2. Introduce a cluster-aware scheduler service that enqueues review requests and only dispatches when slots are available (manual/merge-blocking runs get priority).
3. Persist queue metadata (in-memory + short-term AO table) so UI can show pending runs; add admin REST endpoints to inspect/flush the queue.

### 2. Per-Repo/Project Rate Limiting
1. Extend `AIReviewServiceImpl` entry points with a token-bucket limiter keyed by project/repo to prevent a single repository from saturating the cluster.
2. Surface “throttled” events in progress history so users understand delays.

### 3. Bounded Worker Pools & Async Execution
1. Move diff collection + AI orchestration to dedicated executors configured via the new concurrency limits.
2. Ensure request threads return quickly by handing off work; expose job IDs that the UI polls.

### 4. AI Backend Resilience
1. Wrap AI client calls with resilience4j-style circuit breakers + retry/backoff policies; treat vendor 429/5xx separately.
2. Cache overview responses keyed by commit hash to avoid duplicate processing on re-reviews.
3. Emit metrics for AI latency, error rates, and retry counts.

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

> **Next Steps:** add the per-project/repo rate limiter (Token bucket) and then move on to AI backend resilience (circuit breakers + overview cache).
