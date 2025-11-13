# AI Code Reviewer for Bitbucket

## Overview
AI Code Reviewer for Bitbucket brings automated, AI-assisted pull request reviews directly into Bitbucket Data Center. The plugin listens to pull request lifecycle events, streams the diff into an Ollama-compatible large language model, and posts actionable feedback as native Bitbucket comments. Built-in guardrails and administrative tooling help platform teams keep review throughput predictable while still giving developers near-immediate feedback. Use the plugin to shorten review cycles, uncover risky changes earlier, and free human reviewers to focus on architecture and product nuance.【F:src/main/java/com/teknolojikpanda/bitbucket/aireviewer/listener/PullRequestAIReviewListener.java†L23-L158】【F:src/main/java/com/teknolojikpanda/bitbucket/aicode/core/OllamaAiReviewClient.java†L24-L220】

## Features
- **Automatic pull request reviews** triggered on PR open and rescope events with incremental re-review support and draft handling controls.【F:src/main/java/com/teknolojikpanda/bitbucket/aireviewer/listener/PullRequestAIReviewListener.java†L40-L133】
- **Two-pass AI analysis** that produces an overview summary and chunk-level findings, deduplicates results, and posts inline comments with severity metadata.【F:src/main/java/com/teknolojikpanda/bitbucket/aireviewer/service/AIReviewServiceImpl.java†L190-L520】【F:src/main/java/com/teknolojikpanda/bitbucket/aicode/core/TwoPassReviewOrchestrator.java†L24-L147】
- **Progress visibility in the PR view** via an AUI panel backed by a live progress registry and REST polling endpoints, plus an in-progress merge check that keeps merges blocked until analysis completes.【F:src/main/java/com/teknolojikpanda/bitbucket/aireviewer/progress/ProgressRegistry.java†L17-L177】【F:src/main/java/com/teknolojikpanda/bitbucket/aireviewer/hook/AIReviewInProgressMergeCheck.java†L18-L71】【F:src/main/resources/atlassian-plugin.xml†L288-L353】
- **Administrative console** with Configuration, History, Health, and Operations pages for runtime controls, telemetry, cleanup scheduling, and manual triggers.【F:src/main/resources/atlassian-plugin.xml†L226-L320】【F:src/main/java/com/teknolojikpanda/bitbucket/aireviewer/servlet/AdminConfigServlet.java†L46-L124】
- **Guardrails & rate limiting** covering concurrency slots, per-project/repository rate limits, burst credits, worker degradation, and alerting integrations to protect cluster health.【F:src/main/java/com/teknolojikpanda/bitbucket/aireviewer/service/ReviewConcurrencyController.java†L1-L200】【F:src/main/java/com/teknolojikpanda/bitbucket/aireviewer/service/GuardrailsAlertingService.java†L1-L200】
- **Historical insights & auditing** through persisted review runs, chunk telemetry, queue audits, cleanup jobs, and REST access to history and metrics.【F:src/main/java/com/teknolojikpanda/bitbucket/aireviewer/ao/AIReviewHistory.java†L1-L200】【F:src/main/java/com/teknolojikpanda/bitbucket/aireviewer/service/ReviewHistoryCleanupScheduler.java†L1-L155】
- **Manual and automated triggers** exposed through REST endpoints and admin operations for re-running reviews, draining queues, pausing schedulers, and probing model health.【F:src/main/java/com/teknolojikpanda/bitbucket/aireviewer/rest/ManualReviewResource.java†L25-L132】【F:src/main/java/com/teknolojikpanda/bitbucket/aireviewer/service/ModelHealthProbeScheduler.java†L1-L200】

## Installation
1. Download the plugin JAR (`ai-code-reviewer-X.Y.Z.jar`) from your internal distribution or Marketplace channel.
2. Sign in to Bitbucket as a **system administrator**.
3. Navigate to **Administration → Manage apps** and choose **Upload app**.
4. Select the plugin JAR and confirm installation. Active Objects entities are created automatically on first startup.【F:src/main/resources/atlassian-plugin.xml†L11-L83】
5. After installation, verify that the **AI Code Reviewer** section appears in the administration sidebar.

_Manual deployment_: copy the JAR into `<BITBUCKET_HOME>/shared/plugins/installed-plugins/` and restart Bitbucket to load the plugin.

## Configuration
All configuration is available only to Bitbucket system administrators.

### Global settings
1. Go to **Administration → AI Code Reviewer → Configuration**.
2. Provide the **Ollama API base URL**, **primary model identifier**, optional **fallback model**, and API credentials if your Ollama deployment requires them.【F:src/main/java/com/teknolojikpanda/bitbucket/aireviewer/ao/AIReviewConfiguration.java†L18-L87】
3. Adjust **review behavior** (chunk size, parallelism, severity thresholds, draft handling, ignored path patterns, summary output) using the configuration form. Defaults are pre-populated from stored configuration snapshots.【F:src/main/java/com/teknolojikpanda/bitbucket/aireviewer/servlet/AdminConfigServlet.java†L74-L120】
4. Configure **guardrail thresholds** including queue depth caps, per-project/per-repository hourly budgets, burst credit allocations, worker degradation toggle, and alert destinations (Slack/webhook/email).【F:src/main/java/com/teknolojikpanda/bitbucket/aireviewer/ao/AIReviewConfiguration.java†L88-L185】【F:src/main/java/com/teknolojikpanda/bitbucket/aireviewer/service/GuardrailsAlertChannelService.java†L1-L200】
5. Save changes. Updates take effect immediately for new review runs; existing runs continue with their captured configuration snapshot.

### Per-project / per-repository overrides
- Use the **Scope & Rollout** section of the admin UI or call the REST endpoints under `/rest/ai-reviewer/1.0/config/repositories` to enable or disable reviews per project, restrict to allow lists, or assign rollout cohorts for phased adoption.【F:src/main/java/com/teknolojikpanda/bitbucket/aireviewer/rest/RepoConfigResource.java†L1-L200】
- Repository overrides inherit global defaults but can adjust model selection, severity gates, and concurrency priorities when needed.【F:src/main/java/com/teknolojikpanda/bitbucket/aireviewer/ao/AIReviewRepoConfiguration.java†L1-L200】

### Operations and maintenance
- **Operations page**: pause/resume the scheduler, flush queued work, grant temporary burst credits, or force reruns from a searchable queue view.【F:src/main/java/com/teknolojikpanda/bitbucket/aireviewer/servlet/ReviewOperationsServlet.java†L1-L160】
- **Health dashboard**: check model probe status, guardrail incidents, worker node heartbeats, and rate limiter saturation to diagnose capacity issues.【F:src/main/java/com/teknolojikpanda/bitbucket/aireviewer/servlet/ReviewHealthServlet.java†L1-L140】【F:src/main/java/com/teknolojikpanda/bitbucket/aireviewer/service/GuardrailsTelemetryService.java†L1-L200】
- **History page**: search and export historical AI reviews, including chunk-level insights, runtime metadata, and audit trails for compliance reporting.【F:src/main/java/com/teknolojikpanda/bitbucket/aireviewer/servlet/ReviewHistoryServlet.java†L1-L155】

## Usage
### Automatic review lifecycle
1. A developer opens or updates a pull request. `PullRequestAIReviewListener` evaluates configuration (enabled flag, draft policy, scope rules) and enqueues a review with the scheduler.【F:src/main/java/com/teknolojikpanda/bitbucket/aireviewer/listener/PullRequestAIReviewListener.java†L40-L133】
2. `AIReviewServiceImpl` acquires concurrency slots, checks rate limits, snapshots configuration, and prepares diff chunks before delegating to the two-pass orchestrator.【F:src/main/java/com/teknolojikpanda/bitbucket/aireviewer/service/AIReviewServiceImpl.java†L190-L350】
3. The orchestrator requests an overview and chunk analyses from the AI client. Findings are normalized, deduplicated, and mapped back to diff anchors.【F:src/main/java/com/teknolojikpanda/bitbucket/aicode/core/TwoPassReviewOrchestrator.java†L24-L147】
4. Inline comments and a PR summary comment appear automatically, respecting severity thresholds and ignore patterns. Progress updates stream to the PR panel until the job completes.【F:src/main/java/com/teknolojikpanda/bitbucket/aireviewer/service/AIReviewServiceImpl.java†L351-L520】【F:src/main/java/com/teknolojikpanda/bitbucket/aireviewer/progress/ProgressRegistry.java†L17-L177】
5. When processing ends, the merge check releases and reviewers can merge once human review requirements are met.【F:src/main/java/com/teknolojikpanda/bitbucket/aireviewer/hook/AIReviewInProgressMergeCheck.java†L18-L71】

### Manual review & re-run options
- From the **Operations** admin page, select a pull request and click **Run review now** to force a fresh evaluation, optionally bypassing incremental diff logic for full re-analysis.【F:src/main/java/com/teknolojikpanda/bitbucket/aireviewer/servlet/ReviewOperationsServlet.java†L80-L160】
- Automation systems can POST to `/rest/ai-reviewer/1.0/history/manual` with repository and pull request identifiers to trigger out-of-band reviews (requires system administrator permissions).【F:src/main/java/com/teknolojikpanda/bitbucket/aireviewer/rest/ManualReviewResource.java†L25-L132】

### Monitoring progress inside a PR
- Open the pull request and locate the **AI Review** panel (appears in the right-hand sidebar or below the diff depending on theme). The panel displays queue position, chunk progress, last update, and any guardrail delays. Status updates are served by `/rest/ai-reviewer/1.0/progress/<prId>`.
- If a run is paused by guardrails, hover over the warning badge to view the reason (e.g., hourly rate limit exceeded). Administrators can grant burst credits or increase limits from the admin console.【F:src/main/java/com/teknolojikpanda/bitbucket/aireviewer/service/GuardrailsBurstCreditService.java†L1-L200】

### Example team workflow
1. Team Aurora enables AI reviews on their critical services repository with the **blocker-only** severity threshold.
2. A developer raises PR #123 with risky database migration code. The AI review flags a missing rollback path and posts a blocking comment before humans review it.
3. The developer pushes a fix. The listener detects the rescope, the incremental review runs, and only touches the modified files. Guardrail budgets confirm capacity, so the run starts immediately.【F:src/main/java/com/teknolojikpanda/bitbucket/aireviewer/service/ReviewRateLimiter.java†L1-L200】
4. The AI confirms the fix and clears blockers. The merge check releases, the history log records both runs, and the team exports the review artifacts during their weekly compliance audit.【F:src/main/java/com/teknolojikpanda/bitbucket/aireviewer/service/ReviewHistoryService.java†L1-L200】

## Troubleshooting & FAQ
**The AI review never starts.**
- Confirm the plugin is enabled and the configuration page shows the service as **Active**.
- Review queue and rate limits in the Operations page; pending guardrail incidents can defer execution until credits replenish.【F:src/main/java/com/teknolojikpanda/bitbucket/aireviewer/service/ReviewSchedulerStateService.java†L1-L200】【F:src/main/java/com/teknolojikpanda/bitbucket/aireviewer/service/GuardrailsTelemetryService.java†L1-L200】
- Inspect `<BITBUCKET_HOME>/log/atlassian-bitbucket.log` for entries from `com.teknolojikpanda.bitbucket.aireviewer`.

**Comments are missing or incomplete.**
- Check severity thresholds, ignore patterns, and draft-review policies in the Configuration page; the AI may have filtered findings based on these settings.【F:src/main/java/com/teknolojikpanda/bitbucket/aireviewer/ao/AIReviewConfiguration.java†L142-L185】
- Ensure the Ollama endpoint is reachable. Use the Health dashboard to verify model probe status and response latency.【F:src/main/java/com/teknolojikpanda/bitbucket/aireviewer/service/ModelHealthService.java†L1-L200】

**Merge button stays disabled.**
- Open the PR and confirm the AI Review panel shows **Finished**. If a run is stuck, administrators can clear the progress entry via the Operations page or REST progress endpoint.【F:src/main/java/com/teknolojikpanda/bitbucket/aireviewer/progress/ProgressRegistry.java†L120-L177】
- Ensure no manual review is currently running; the merge check vetoes merges for all in-progress runs.【F:src/main/java/com/teknolojikpanda/bitbucket/aireviewer/hook/AIReviewInProgressMergeCheck.java†L18-L71】

**Guardrail alerts fire too frequently.**
- Lower the auto-run frequency by adjusting hourly budgets or enabling auto-snooze for critical repositories in the guardrails section.【F:src/main/java/com/teknolojikpanda/bitbucket/aireviewer/service/GuardrailsAutoSnoozeService.java†L1-L200】
- Tune worker pool size and degradation thresholds from the Operations page or configuration to better absorb traffic spikes.【F:src/main/java/com/teknolojikpanda/bitbucket/aireviewer/service/ReviewWorkerPool.java†L1-L200】【F:src/main/java/com/teknolojikpanda/bitbucket/aireviewer/service/WorkerDegradationService.java†L1-L200】

**Where are logs and metrics stored?**
- Runtime logs go to the Bitbucket application log (`atlassian-bitbucket.log`). Guardrail incidents, queue overrides, and cleanup runs are also persisted in Active Objects tables for later export via the History page.【F:src/main/java/com/teknolojikpanda/bitbucket/aireviewer/ao/AIReviewQueueAudit.java†L1-L200】【F:src/main/java/com/teknolojikpanda/bitbucket/aireviewer/ao/AIReviewCleanupAudit.java†L1-L200】

For support, capture relevant log excerpts, the response from `/rest/ai-reviewer/1.0/monitoring/health`, and recent guardrail incidents before contacting your platform team or plugin vendor.【F:src/main/java/com/teknolojikpanda/bitbucket/aireviewer/rest/MonitoringResource.java†L1-L200】
