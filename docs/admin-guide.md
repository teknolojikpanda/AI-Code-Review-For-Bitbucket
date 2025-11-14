# Admin Guide

This guide targets Bitbucket system administrators responsible for installing, configuring, and operating AI Code Reviewer.

## Installation

1. **Verify prerequisites**
   - Bitbucket Server/Data Center 8.15 or later (developed and tested on 9.6.5).
   - Java 8 runtime on all application nodes.
   - Network reachability from every node to the Ollama host configured for model inference.
   - Database permissions that allow Bitbucket to create Active Objects tables (the plugin stores configuration and telemetry in `AI_REVIEW_*` and `GUARDRAILS_*`).
2. **Obtain the plugin JAR**
   - Build locally with `mvn package` or download the signed artefact from your internal distribution site.
3. **Upload via the Universal Plugin Manager (UPM)**
   - Navigate to **Administration → Manage apps → Upload app** and select the JAR.
   - Confirm that the plugin appears under **User-installed apps** and is enabled.
4. **Post-install validation**
   - Visit **Administration → AI Code Reviewer** to confirm the four navigation items: **Configuration**, **Review History**, **Health**, and **Operations**.
   - Check `atlassian-bitbucket.log` for messages about completed database migrations or any reported schema issues.

## Global Configuration

All global settings live under **Administration → AI Code Reviewer → Configuration**. The servlet persists configuration via `AIReviewerConfigService` and validates every change before saving.

### Model & Connection

- **Ollama URL / Model / Fallback Model**: define the primary and fallback models. Defaults are `http://0.0.0.0:11434`, `qwen3-coder:30b`, and `qwen3-coder:7b`.
- **Timeouts**: `connectTimeout`, `readTimeout`, `ollamaTimeout`, and `apiDelayMs` throttle outbound requests.
- Use **Test connection** to verify the configured endpoint before saving.

### Review Behaviour

- **Chunking**: `maxCharsPerChunk`, `maxFilesPerChunk`, `maxChunks`, `parallelThreads`, and `maxParallelChunks` control how the two-pass orchestrator splits diffs.
- **Retry policy**: `maxRetries`, `chunkMaxRetries`, `overviewMaxRetries`, and associated delays manage resilience when Ollama is slow.
- **Content filters**: `skipGeneratedFiles`, `skipTests`, `reviewExtensions`, `ignorePatterns`, and `ignorePaths` limit which files are reviewed.
- **Severity thresholds**: `minSeverity` filters findings; `requireApprovalFor` lists severities that must receive a human approval before merge.
- **Service identity**: `aiReviewerUser` sets the Bitbucket account used to author comments. If blank, the plugin impersonates the triggering user.

### Guardrails & Capacity

- **Concurrency**: `maxConcurrentReviews` bounds simultaneous runs; `maxQueuedReviews`, `maxQueuedPerRepo`, and `maxQueuedPerProject` protect the worker queue.
- **Rate limits**: `repoRateLimitPerHour` and `projectRateLimitPerHour` restrict scheduled runs per hour.
- **Priority scopes**: `priorityProjects`, `priorityRepositories`, and `priorityRateLimitSnoozeMinutes` grant temporary relief when throttling occurs.
- **Burst credits**: grant ad-hoc capacity spikes from the Operations page or the `/automation/burst-credits` REST endpoints.
- **Worker degradation**: toggle `workerDegradationEnabled` to let the worker pool slow itself when utilisation remains high.

### Scope & Profiles

- **Scope mode**: select between `ALL`, `ALLOW_LIST`, or `BLOCK_LIST` to control which repositories are reviewed.
- **Repository overrides**: each repository can override any subset of configuration keys via the overrides table or the `/config/repositories` REST resource. Undefined values inherit the global defaults.
- **Review profiles**: choose presets such as `balanced` or `strict`, or fine-tune severity filters manually.

## Operations Surfaces

### Review History

- Lists completed, running, and failed reviews with filters for project, repository, status, and manual versus automated runs.
- Provides CSV export of cleanup audit data and on-demand cleanup runs via `/rest/ai-reviewer/1.0/history/cleanup`.
- Administrators can trigger manual reviews and inspect chunk-level telemetry from this page.

### Health Dashboard

- Aggregates worker node heartbeats (`GuardrailsWorkerNodeState`), Ollama health probes (`ModelHealthService`), and scheduler state.
- Highlights when the model fallback is active, when circuit breakers trip, and when queue saturation thresholds are exceeded.

### Operations Console

- Pauses, drains, or resumes the scheduler (`/progress/admin/scheduler/*`).
- Manages rollout cohorts for gradual enablement of projects or repositories (`/automation/rollout/*`).
- Configures alert channels (e-mail, webhook) and acknowledges deliveries via `/automation/channels` and `/automation/alerts/deliveries`.
- Grants or revokes burst credits and reviews queue audits.

## Permissions

- Only **system administrators** may access the admin servlets or call privileged REST endpoints.
- Repository administrators can read live progress and review history but cannot modify global settings unless delegated via REST overrides.
- Manual review triggers (`/history/manual`) require system administrator privileges.

## Upgrades & Maintenance

- Always back up Bitbucket’s database (especially the `AO_` tables) before upgrading.
- Upload the new JAR via UPM; Active Objects schema upgrades run automatically at startup.
- In a cluster, the review queue persists. Use the Operations console to pause the scheduler and drain in-flight work before maintenance windows or backend outages.
- Regular cleanup jobs purge aged history (`AIReviewCleanupStatus`, `AIReviewCleanupAudit`). Trigger ad-hoc cleanup if storage pressure arises.

## Operational Tips

- Enable DEBUG logging for `com.teknolojikpanda.bitbucket` temporarily when diagnosing issues, then revert to INFO to reduce noise.
- Monitor Bitbucket logs for `Guardrails` alerts; configure alert channels so incidents reach the on-call team.
- If the Ollama backend is offline, pause the scheduler to prevent repeated retries, then resume when the backend recovers.
- Export configuration snapshots using `/rest/ai-reviewer/1.0/config` to store change history and speed up disaster recovery.

For scripting and integration details, refer to the [REST API Reference](api/rest-api.md). Configuration keys and defaults are catalogued in the [Configuration Reference](configuration-reference.md).
