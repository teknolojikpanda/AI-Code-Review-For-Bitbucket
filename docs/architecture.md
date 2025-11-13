# Guardrails Architecture Overview

This document explains how the Guardrails plugin is wired inside Bitbucket Data Center: the UI entry points, service layers, background jobs, and data stores. Use it as the canonical map when onboarding new contributors or reviewing PRs.

## High-level Diagram

```mermaid
flowchart TD
    subgraph UI
        AdminConfig[Admin Config (Velocity + JS)]
        AdminOps[Operations Dashboard]
        RESTClients[guardrails-cli.sh / rollout.sh]
    end

    subgraph Bitbucket Plugin
        REST[REST Resources (ConfigResource, AutomationResource, ProgressResource)]
        Services[Service Layer (AIReviewServiceImpl, ReviewConcurrencyController, RateLimiter, GuardrailsRolloutService)]
        Core[aicode Core (ChunkPlanner, Orchestrator, Ollama client)]
        Background[Schedulers (ModelHealthProbe, HistoryCleanup, ReviewSchedulerState)]
    end

    subgraph DataStores
        AO[(Active Objects Tables)]
        Runtime[In-memory state (worker pool, queues, caches)]
    end

    AdminConfig --> REST
    AdminOps --> REST
    RESTClients --> REST
    REST --> Services
    Services --> Core
    Services --> Background
    Services --> AO
    Background --> AO
    Core --> Runtime
    Services --> Runtime
```

## Component Breakdown

### UI Layer
- `src/main/resources/templates/admin-config.vm` + `js/ai-reviewer-admin.js`: Configuration screen, scope tree, feature flags.
- `templates/admin-ops.vm` + `js/ai-reviewer-ops.js`: Operations dashboard (queue, rollout, alert channels, retention tools).
- Velocity templates only render scaffolding; all dynamic behaviour is in the corresponding JS files that call `rest/ai-reviewer/1.0/*`.

### REST Resources
- `ConfigResource`: CRUD for reviewer settings, rate-limit overrides, scope management.
- `ProgressResource`: Queue snapshots, running reviews, filter breakdowns.
- `AutomationResource`: Scheduler state, alert channels, burst credits, rollout cohorts.
- `MetricsResource`, `AlertsResource`: Telemetry endpoints consumed by the health UI/CLI.

### Service Layer Highlights
- `AIReviewServiceImpl`: Orchestrates the entire review lifecycle (queue acquisition, worker submission, chunk planning, AI calls, telemetry).
- `ReviewConcurrencyController`: Global + per-scope queue enforcement, active run tracking, admin cancel actions.
- `ReviewRateLimiter`: Token bucket limiter backed by AO tables with auto-snooze + burst credits (`GuardrailsBurstCreditService`).
- `GuardrailsRolloutService`: Cohort evaluation, dark feature integration, telemetry describing which repositories run in enforced/shadow/fallback modes.
- `GuardrailsTelemetryService`: Aggregates queue/worker/limiter/cleanup/model-health summaries for the Health UI and `/metrics`.

### Core AI Modules (`com.teknolojikpanda.bitbucket.aicode`)
- `HeuristicChunkPlanner` filters files (ignore paths/patterns, skip generated/tests) and splits diffs into `ReviewChunk`s.
- `TwoPassReviewOrchestrator` combines chunk execution with overview generation and fallback handling.
- `OllamaAiReviewClient` handles HTTP calls to the configured model endpoints with circuit breaker/retry behaviour.

### Background Jobs
- `ModelHealthProbeScheduler`: Periodically pings model endpoints and updates `ModelHealthService` so the reviewer can fail over degraded models.
- `ReviewHistoryCleanupScheduler` / `MaintenanceService`: Enforce retention policies, export data, repair integrity issues.
- `ReviewSchedulerStateService`: Persists pause/drain/active state in AO and emits audit records.

### Active Objects Schema
Key tables (all defined under `src/main/java/.../ao`):
- `AIReviewConfiguration`, `AIReviewRepoConfiguration`: Global and per-repo settings.
- `AIReviewHistory`, `AIReviewChunk`: Persisted review results + per-chunk metrics.
- `AIReviewQueueAudit`: Admin queue actions + scheduler changes.
- Guardrails-specific: `GuardrailsRateBucket`, `GuardrailsRateOverride`, `GuardrailsBurstCredit`, `GuardrailsAlertChannel/Delivery`, `AIReviewRolloutCohort`.

### Runtime State
- Worker pool (`ReviewWorkerPool`) + progress tracker (`ProgressRegistry`) hold active runs, SSE updates, and telemetry timelines.
- Rate limiter caches (short-lived) keep bucket counters in sync across cluster nodes.

## Data Flow Summary
1. **Config change**: Admin UI → `ConfigResource` → `AIReviewerConfigServiceImpl` → AO tables → distributed caches.
2. **Review request**: PR hook/manual run → `AIReviewServiceImpl` → queue controller → worker pool → chunk planner → orchestrator → Ollama → history persistence.
3. **Operations dashboard**: JS polls `/progress/admin/queue` + `/automation/*` + `/monitoring/runtime`.
4. **Rollout cohort**: `GuardrailsRolloutService` evaluates scope + dark feature + sampling to decide whether guardrails enforce or shadow for a run; telemetry records per-cohort usage.
5. **Alerts + CLI**: `guardrails-cli.sh` hits REST endpoints; output mirrors same payloads the UI consumes (JSON rendered with `jq` for readability).

Understanding these flows should make it clear where to add new features (e.g., plug another limiter into `ReviewRateLimiter`, extend telemetry via `GuardrailsTelemetryService`, or add UI cards in `admin-ops.vm`).
