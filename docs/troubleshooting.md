# Troubleshooting

Use this guide to diagnose and resolve common issues with AI Code Reviewer.

## Logs and Diagnostics

- **Application logs**: check `$BITBUCKET_HOME/log/atlassian-bitbucket.log` for entries tagged with `com.teknolojikpanda.bitbucket`.
- **Structured logging**: messages include key-value pairs (e.g., `review.trigger`, `pullRequestId`) to help correlate events.
- **Debug logging**: temporarily set package logging to DEBUG via **Administration → System → Logging** for `com.teknolojikpanda.bitbucket`. Revert to INFO after capturing details.
- **Progress snapshots**: query `GET /rest/ai-reviewer/1.0/progress/{project}/{repo}/{pr}` to inspect the live run state.
- **Metrics**: fetch `GET /rest/ai-reviewer/1.0/metrics` for queue sizes, worker utilisation, and circuit breaker status.

## Common Problems

### Progress Panel Missing

- Confirm the plugin is enabled and web resources load (browser console should not report missing `ai-reviewer-pr-progress.js`).
- Ensure you have read access to the repository; the REST endpoint returns 403 for unauthorised users.
- Verify that AI reviews are enabled in configuration (`enabled = true`) and that the repository falls within the configured scope.

### Reviews Never Start

- Check the Operations console for scheduler mode. If paused or draining, resume via **Operations → Scheduler → Resume** or `POST /rest/ai-reviewer/1.0/progress/admin/scheduler/resume`.
- Inspect queue limits: if `maxQueuedReviews`, `maxQueuedPerRepo`, or rate limits are exceeded, the review remains queued until capacity frees up.
- Confirm Ollama connectivity using the **Test connection** button or `POST /rest/ai-reviewer/1.0/config/test-connection`.
- Review `GuardrailsRateIncident` entries via `/rest/ai-reviewer/1.0/config/limiter` for throttling explanations.

### Review Fails with Errors

- `review.failed` log entries include stack traces and context. Typical causes:
  - Diff exceeds `maxDiffSize` or `maxChunks`.
  - Ollama requests timed out; adjust `ollamaTimeout` or scale the backend.
  - Manual run attempted without administrator privileges (`ManualReviewResource` returns 403).
- Look for circuit breaker messages (`CircuitBreaker` or `ai.model.circuit`) indicating repeated model failures; fallback model may activate automatically.

### Merge Blocked by “AI Review In Progress”

- Use the progress panel or `GET /progress/{project}/{repo}/{pr}` to ensure the review completes. The merge check releases automatically when status becomes terminal.
- If the review stalled, cancel it via `POST /rest/ai-reviewer/1.0/progress/admin/running/cancel` (requires admin rights) or drain the scheduler.
- Investigate worker health on the Health dashboard; restart workers if heartbeats are stale.

### Configuration Changes Not Taking Effect

- Check `atlassian-bitbucket.log` for validation warnings (`config.validation` or `Unsupported configuration key`).
- Ensure repository overrides are not overriding the global value you changed.
- Retrieve the effective configuration with `GET /rest/ai-reviewer/1.0/config` to confirm persisted values.

### Cleanup Jobs Stuck

- `GET /rest/ai-reviewer/1.0/history/cleanup/status` exposes the current cleanup state (`AIReviewCleanupStatus`).
- Trigger a manual cleanup with `POST /rest/ai-reviewer/1.0/history/cleanup`. Errors are logged under `review.cleanup`.
- If the scheduler is paused, cleanup jobs will not execute until it resumes.

## Collecting Support Data

When escalating to maintainers:

1. Export configuration (`GET /rest/ai-reviewer/1.0/config`).
2. Capture relevant log excerpts containing `review.*`, `guardrails.*`, or `manual_review.*` entries.
3. Provide IDs for affected reviews (`AIReviewHistory` records) so maintainers can replay or inspect chunk telemetry.
4. Note Bitbucket version, plugin version, and Ollama model versions.

## Resetting the Plugin

- Disable the plugin via UPM, then re-enable it to reload components. Active Objects data is preserved.
- To purge state (use with caution), remove `AI_REVIEW_*` and `GUARDRAILS_*` tables after taking a backup; upon re-enabling, defaults are recreated.

Refer to the [FAQ](faq.md) for quick answers to recurring questions.
