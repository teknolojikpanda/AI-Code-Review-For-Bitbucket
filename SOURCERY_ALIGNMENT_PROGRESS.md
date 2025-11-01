# Sourcery Alignment Progress

## 2025-10-27

- Created baseline git checkpoint (`chore: checkpoint before sourcery ai alignment`).
- Drafted alignment plan outlining current gaps, target capabilities, and phased roadmap.
- Confirmed CRLF diff handling fix in `HeuristicChunkPlanner` to ensure full file coverage prior to enhancements.
- Added toggleable diagnostics (`ai.reviewer.diagnostics=true`) capturing raw diffs, per-file diffs, and chunk compositions to aid diff/partition debugging.
- Instrumented planner with additional diagnostics for candidate files and extracted per-file hunks to chase missing-in-chunk issues surfaced in PR #24.
- Updated Ollama client to treat repeated chat failures (timeouts, JSON parse errors, missing fallback model) as empty results instead of aborting the chunk, and log a concise root cause. This fixes the “Chunk chunk-0 failed: All model invocations failed” regression when the fallback model is absent.
- Added resilience around Ollama `EOF` failures: chunk payloads are automatically truncated (20k chars) and retried when the server closes connections mid-stream, and repeated “model not found” responses are cached to avoid hammering unavailable models.

## 2025-10-28

- Committed AI comment anchoring change to place feedback on the closing line when multiline ranges are reported (Bitbucket rendering improvement).
- Drafted Phase 1 backlog (`PHASE1_BACKLOG.md`) detailing actionable tasks for chunking strategies, prompt templates, and finding validation.
- Updated alignment plan to reference the new backlog so status can be tracked per workstream.
- Introduced the `ChunkStrategy` interface (Phase 1 / C1) to decouple chunk planning heuristics from the current `HeuristicChunkPlanner`.
- Refactored `HeuristicChunkPlanner` to delegate chunk planning to `SizeFirstChunkStrategy`, completing backlog item C2.
- Added file metadata extraction (Phase 1 / C3) to capture language, directories, churn, and test flags for each reviewed file.
- Externalised prompt templates (Phase 1 / P1) and wired them into `OllamaAiReviewClient`, enabling configurable system/overview/chunk prompts.
- Enriched chunk instructions with contextual metadata (Phase 1 / P2), surfacing per-file language, churn, and test indicators to the AI model.
- Added diff position resolver (Phase 1 / F1) ensuring issue line numbers map to real hunks before comments are posted.
- Implemented issue fingerprinting (Phase 1 / F2) to deduplicate repeated AI findings by path/line/summary.
- Documented migration plan for duplicate fingerprint store (Phase 1 / F3) in `docs/duplicate_fingerprint_migration_plan.md`.
- Built templated summary comment renderer (Phase 2 / T1) using markdown partials for headers, tables, and per-file issue listings.

## 2025-10-29

- Landed review profile presets (Balanced, Security First, Lightweight) with persisted selection and validation (Phase 2 / item 3). Configuration defaults now capture the chosen profile key and auto-approve preference.
- Extended `/rest/ai-reviewer/1.0/config` to return profile descriptors so the admin UI can present guidance and defaults.
- Refreshed the admin configuration UI with a profile selector, live preset summaries, and a new auto-approve toggle that mirrors the selected preset defaults while supporting manual overrides.
- Added `SummaryInsights` aggregation (Phase 2 / item 2) to drive severity, category, and guidance messaging in the summary comment, exposing medium/high level review advice and top issue categories.
- Reordered comment posting so inline issues land first and the summary comment is published last, improving readability in Bitbucket’s activity feed (Phase 2 / item 2 refinement).
- Exposed `/rest/ai-reviewer/1.0/history` with filtering and delivered a lightweight `ReviewHistoryService` so admins can query recent reviews (Phase 2 / item 4).
- Added `/rest/ai-reviewer/1.0/config/auto-approve` to toggle the auto-approval flag without resubmitting the full configuration payload (Phase 2 / item 4).
- Registered the history service in OSGi and marked it singleton so REST injection succeeds (fix for `/rest/ai-reviewer/1.0/history` startup failure).
- Moved review history into its own admin page backed by the new REST endpoints, added basic filtering, and kept an inline auto-approve toggle on the config screen (Phase 2 / item 4 UI integration).

## 2025-10-30

- Authored `docs/observability_dashboard_design.md`, detailing data model migrations, REST endpoints, and UI layout required for the Phase 3 observability dashboard (Next Steps item 1).
- Defined implementation plan covering metrics instrumentation, AO migrations for chunk-level data, administrative REST APIs, and admin UI enhancements to chart review performance.
- Extended runtime instrumentation: `MetricsCollector` now captures list-based chunk invocation entries, and `OllamaAiReviewClient` records per-model success/failure counters plus fallback triggers for dashboard consumption.
- Added `AIReviewChunk` AO entity, expanded `AIReviewHistory` with profile and model counter fields, and updated `AIReviewServiceImpl` to persist per-chunk invocation metadata alongside fallback statistics (Phase 3 observability groundwork).
- Exposed detailed history endpoints: `ReviewHistoryService` now returns per-entry chunk metadata, and `HistoryResource` serves `/history/{id}` plus `/history/{id}/chunks`, enabling the admin UI to query chunk timelines.
- Hardened configuration validation (Phase 3 / item 3): introduced structured `ConfigurationValidationException`, tightened numeric range checks, validated prompt overrides, and updated REST responses to surface field-level errors.
- Refreshed admin history UI to consume new observability data: table rows load detailed review metadata, model statistics, and chunk invocation timelines via the new REST endpoints, with a responsive summary panel.
- Added `/history/metrics` aggregate endpoint and summary cards on the admin history page, surfacing total reviews, duration percentiles, issue counts, and fallback usage derived from persisted telemetry.
- Added configurable page size options (10/100/1000/All) with corresponding server-side pagination so large history result sets load incrementally without blocking the UI.
- Surfaced daily trend metrics via `/history/metrics/daily` and rendered a daily activity table on the admin dashboard for quick review cadence monitoring.
- Added a daily activity sparkline to the metrics section for quick visual trend scanning.
- Exposed primary/fallback success rates in the summary metrics card to highlight model reliability trends.
- Instrumented chunk telemetry with request/response sizes, HTTP status codes, and timeout flags, persisting them to `AIReviewChunk` and surfacing the data in the admin chunk detail view.
- Added chunk-telemetry backfill endpoint and service to repopulate legacy history entries; metrics summary now aggregates total bytes/timeouts.
- Ensure new telemetry columns are created on startup by migrating `AIReviewChunk` within the service constructor.

## Next Steps

- Implement metrics capture enhancements and persistence migrations outlined in the observability dashboard design.
- Harden configuration validation for prompt/profile fields and expose clear errors (Phase 3 / item 3).
- Plan implementation schedule for fingerprint persistence (Phase 1 / F3) based on the documented migration approach.
