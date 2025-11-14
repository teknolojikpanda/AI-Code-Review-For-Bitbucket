# Admin Guide

This guide targets Bitbucket system administrators responsible for installing, configuring, and operating AI Code Reviewer.

## Installation

1. **Verify prerequisites**
   - Bitbucket Server/Data Center 9.6.5 or later (developed and tested on 9.6.5).
   - Java 17 runtime on all application nodes (the minimum supported JVM for Bitbucket 9.6.5+).
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

## Administrative Pages

The plugin installs four administrative screens under **Administration → AI Code Reviewer**. Each page is backed by the correspo
nding REST resource so you can also integrate the functionality into automation tooling.

### Configuration

- **Purpose**: establish global defaults, manage model connectivity, and define guardrails that apply across all projects.
- **Layout**:
  - **Connection card**: configure primary and fallback Ollama endpoints, transport timeouts, and the service account used to po
st comments. The **Test connection** action validates credentials and latency before you commit changes.
  - **Review behaviour card**: tune chunking limits, retry policies, and severity filters. Inline validation warns when values e
xceed supported thresholds or conflict with project overrides.
  - **Guardrails card**: manage concurrency ceilings, queue limits, and repository scope (all/allow list/block list). When the c
urrent queue is close to the configured limits the UI displays warning badges so you can react before requests are rejected.
  - **Overrides table**: lists repositories with custom settings and highlights keys that deviate from the global defaults. From
 here you can open the repository override dialog or navigate to the REST endpoint for scripted updates.
- **Usage tips**:
  - Save changes in small batches and export the configuration JSON afterwards for audit purposes.
  - When enabling review of draft pull requests or increasing chunk limits, coordinate with the infrastructure team to ensure t
he Ollama backend has enough capacity.
  - Use the **Effective settings** fly-out to troubleshoot why a specific repository is behaving differently; it merges global,
 project, and repository overrides in precedence order.

### Review History

- **Purpose**: audit review execution and re-run analyses outside the pull request context.
- **Key sections**:
  - **Filters**: slice by project, repository, status, trigger type, reviewer cohort, or date range. Filters persist across sess
ions per administrator to speed up investigations.
  - **Timeline table**: lists the last 90 days of runs with timestamps, duration, triggering actor, commit hash, and the count o
f findings posted. Status badges highlight guardrail skips versus provider failures.
  - **Detail drawer**: selecting a run reveals chunk-level telemetry, retry attempts, and links to raw provider transcripts (obf
uscated for PII) for compliance review.
  - **Actions**: rerun a review, cancel an in-progress execution, or purge stored artifacts for the selected pull request. These
 actions call the `/history/manual`, `/progress/admin/cancel`, and `/history/purge` REST routes respectively.
- **Usage tips**:
  - Use the history table during incident response to quantify impact (number of failed runs, repositories affected).
  - Export CSV snapshots when preparing change management records or audits; exports include the configuration checksum applied
 to each run.
  - When comparing runs, sort by commit hash to confirm that regressions correspond to specific pushes.

### Health Dashboard

- **Purpose**: monitor the health of the AI review service and its dependencies.
- **Widgets**:
  - **Service connectivity**: shows the last successful call to each configured model, average latency, and failure trends. The
 UI flags HTTP error codes, TLS negotiation issues, or authentication failures.
  - **Worker pool utilisation**: visualises queue depth, active workers, and throttle states aggregated from `GuardrailsWorkerNo
deState` records.
  - **Guardrail status**: summarises rate-limit enforcement, repository allow/block list hits, and burst credit consumption so y
ou can identify systemic throttling.
  - **Event log**: stream of notable events (fallback activation, circuit breaker trips, manual pauses) with links to related re
st endpoints for quick remediation.
- **Usage tips**:
  - Investigate spikes in request latency before developers encounter timeouts. The chart retains 24 hours of history for trend
 analysis.
  - Use the **Download diagnostics** button to collect the latest health snapshot and attach it to support tickets.
  - If a node stops reporting heartbeats, drain the scheduler and failover traffic before restarting the affected application no
de.

### Operations Console

- **Purpose**: perform privileged actions that affect the scheduler, queue, and rollout cadence.
- **Capabilities**:
  - **Scheduler controls**: pause, resume, or drain the review scheduler. Pausing prevents new runs while letting active jobs fi
nish; draining stops scheduling and cancels remaining queue entries.
  - **Rollout management**: assign repositories or projects to rollout cohorts, set cohort-specific guardrails, and schedule gra
dual enablement windows. The console surfaces the REST payloads so you can mirror changes in infrastructure-as-code.
  - **Alerting and burst credits**: configure webhook or e-mail alert channels, acknowledge incident notifications, and grant bu
rst credits when teams need temporary capacity boosts.
  - **Data hygiene**: trigger cleanup jobs, purge stale history, or archive AI responses to S3 via configured automations.
- **Usage tips**:
  - Always pause the scheduler before performing Bitbucket maintenance or rotating Ollama credentials to avoid partial runs.
  - Document burst credit grants and roll them back once the queue normalises to keep guardrail metrics accurate.
  - Limit console access to on-call engineers; actions take effect cluster-wide and may impact SLAs.

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
