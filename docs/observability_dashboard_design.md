# Observability Dashboard Design

## Objectives

- Surface historical AI review performance so administrators can spot regressions in latency, failure rates, and issue volume.
- Provide actionable drill-downs (per-review metrics, chunk-level failures, retry counts) comparable to Sourcery’s telemetry.
- Enable remediation workflows: re-queue failed chunks, export metrics, and trace configuration changes that impacted model behaviour.

## Existing Foundations

- **Persistence:** `AIReviewHistory` already stores per-review metadata and a JSON metrics snapshot (`metricsJson`) produced by `MetricsCollector`.
- **Service Layer:** `ReviewHistoryService#getHistory` exposes history rows with optional filters, returning trimmed metric payloads.
- **REST/UI:** `/rest/ai-reviewer/1.0/history` feeds the admin history page, but the UI currently renders a flat table without charts or deeper metrics.

## Dashboard Scope

1. **Overview timeline**
   - Chart review duration, chunk counts, and issue totals over time (per PR execution).
   - Highlight reviews flagged as PARTIAL/FAILED with warning markers.
2. **Performance analytics**
   - Aggregated percentiles (p50/p95) for `ai.chunk.call`, `diff.bytes`, `postComments`.
   - Model success vs. fallback usage counts.
3. **Reliability signals**
   - Number of retries per review (`ai.chunk.attempt` vs. `chunks.succeeded`).
   - Failed chunk identifiers with error excerpts.
4. **Operations tools**
   - Quick filters by project/repository/profile.
   - Button to re-run a review or reprocess failed chunks (Phase 3 stretch).
   - Export (CSV/JSON) of filtered metrics.

## Data Model Enhancements

| Requirement | Approach |
|-------------|----------|
| Aggregated metrics | Extend `AIReviewHistory` to store derived percentile fields (optional) or compute on the fly using AO queries. |
| Chunk-level detail | Persist condensed chunk records in a new AO table (`AIReviewChunk`) referencing history entries, capturing model, duration, retries, error. |
| Retry metadata | Update `MetricsCollector` to emit per-chunk retry counts and last error message into `metricsJson`. |
| Profile & config context | Persist the active review profile key and auto-approve flag for each history row (new columns). |

### Migration Steps

1. Add AO entity `AIReviewChunk` (historyId, chunkId, model, status, durationMs, retries, lastError, created).
2. Backfill existing `metricsJson` into chunk rows by parsing stored snapshots (best-effort, skip if absent).
3. Extend `AIReviewHistory` with `profileKey`, `autoApproveEnabled`, `fallbackCalls`, `primaryCalls`.

## API Extensions

- **GET `/rest/ai-reviewer/1.0/history/metrics`**
  - Params: projectKey, repositorySlug, profileKey, since, until.
  - Returns: aggregated statistics (totals, percentiles, issue breakdown).
- **GET `/rest/ai-reviewer/1.0/history/{id}`**
  - Returns full review record with parsed metrics plus chunk summaries.
- **GET `/rest/ai-reviewer/1.0/history/{id}/chunks`**
  - Paginates through chunk records (model, duration, retries, errors).
- **POST `/rest/ai-reviewer/1.0/history/{id}/retry`** *(stretch)*
  - Enqueues a re-review using saved configuration.

### Response Considerations

- Trim metric payloads server-side to avoid multi-MB responses.
- Support `Accept: text/csv` on history list for exports.
- Add caching headers (`Cache-Control: no-store`) because metrics are sensitive.

## Frontend Design

- **Framework:** Leverage existing AUI-based admin pages; integrate lightweight charting via Atlassian Chart.js bundle.
- **Layout:**
  1. **Header filters:** project, repository, profile, date range.
  2. **Summary cards:** totals for reviews, failures, avg duration, auto-approve rate.
  3. **Timeline chart:** stacked area (chunk duration vs. diff bytes) with severity markers.
  4. **Issue distribution:** donut chart by severity & category.
  5. **Chunk reliability table:** sortable list showing retries, fallback usage, last error.
  6. **Drill-down drawer:** when selecting a review, show metrics breakdown and chunk list.
- **Interactions:**
  - Hover tooltips for metrics.
  - Download button exporting visible dataset.
  - “Re-run review” action surfaced in drawer (disabled until backend ready).

## Security & Permissions

- Restrict endpoints to Bitbucket system administrators (mirror existing history/config access).
- Ensure AO queries enforce resource filters to prevent data leakage across projects.
- Sanitize error messages prior to persistence to avoid storing tokens or credentials.

## Implementation Plan

1. **Metrics capture improvements**
   - Instrument `MetricsCollector` to track retries/errors per chunk and fallback invocation counts.
   - Extend `AIReviewServiceImpl` to populate new history columns and chunk table.
2. **Persistence migrations**
   - Ship AO migration for new columns and chunk table.
   - Add background backfill job parsing recent history (optional, skip if data missing).
3. **REST enhancements**
   - Introduce DTOs for summaries, chunk stats, percentile computations.
   - Implement `/history/metrics` endpoint with aggregation queries.
4. **Frontend**
   - Update admin UI to fetch metrics endpoints, render filters, and display charts.
   - Add chunk drill-down drawer with retry/export controls.
5. **Testing**
   - Integration tests covering REST filtering & aggregation.
   - UI tests (Selenium/WebDriver or Jest if feasible) for filtering and chart rendering.
   - Performance testing to validate AO queries under large history datasets (>5k rows).

## Open Questions

- Do we need tenant-level storage quotas for history/chunk records?
- Should metrics snapshots be anonymised before export (e.g., remove file paths)?
- What retention period should default to (configurable purge job)?

## Deliverables

- AO migration scripts + entity updates.
- Enhanced metrics instrumentation.
- REST endpoints and DTOs.
- Updated admin UI dashboard with charts and actions.
- Documentation for administrators (usage, retention settings).
