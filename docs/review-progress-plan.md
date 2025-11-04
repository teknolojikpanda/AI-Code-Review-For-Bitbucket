# Pull Request Progress Improvements Roadmap

## Objective

Deliver a polished, informative, and accessible AI review progress experience directly on pull requests while keeping the admin history page focused on completed runs.

## Current Status (Phase 0)

- Floating PR overlay renders automatically on pull request pages when the feature toggle and scope conditions are satisfied.
- Panel polls `/rest/ai-reviewer/1.0/progress/{project}/{repo}/{pr}` and displays live events or a waiting message when no review is active.
- Admin history page shows historical timelines only (live monitor removed per request).

## Iteration Plan

### Iteration 1 – Status Polish & UX Foundations

- [x] Collapse completed runs into a compact status summary (badge + timestamp + event count) while keeping the full timeline accessible on expand.
- [x] Surface clear state indicators (running, completed, failed, waiting) with color-coded badges and accessible text.
- [x] Add ARIA roles and live-region updates so progress changes are announced by screen readers; verify dark-theme compatibility.
- [x] Provide a collapse/expand toggle plus animation-free transitions for minimal distraction.

### Iteration 2 – Historical Insight & Drill-down

- [x] Add a "Recent Runs" dropdown within the panel that lists the last N history entries and loads their timelines via `GET /progress/history/{historyId}`.
- [x] Enable per-event expansion to reveal chunk-level diagnostics (model, attempts, duration, failure reason) sourced from the history REST payload.
- [x] Cache the most recent snapshot per PR so the overlay renders immediately on revisit without waiting for the first poll.
- [x] Update backend pagination on `/progress/history` to cap the payload while still exposing the metadata needed for the dropdown (run id, status, finished timestamp, duration).
- [x] Record design notes for the dropdown interaction (keyboard access, truncation rules, empty state copy) before implementing the UI.
- [x] Ensure registry snapshots carry enough information for the cache to pre-populate the panel (include final summary strings and run timestamps).
- [x] Instrument progress polling to log cache hit/miss ratios and average hydration times so we can verify the cache actually improves perceived latency.
- [x] Add REST contract tests guarding the new `/progress/history` schema to avoid regressions once consumers start depending on the dropdown data.
- [x] Ship feature behind a dedicated `ai.reviewer.progress.iteration2` flag with kill-switch documentation.
- [ ] Define rollout metrics (progress view open rate, dropdown usage) and wire them into analytics if available.
- [ ] Prepare customer-facing release notes summarising the drill-down capabilities and migration steps.

#### Dropdown Interaction Notes

- Keyboard support: `<select>` remains focusable; history rows load on change; play/pause buttons retain `aria-label`.
- Truncation: option labels capped at ~120 characters with ellipsis via frontend helper to avoid overflow.
- Empty state: message `No previous runs available yet.` appears below selector; control disabled until data present.
- Refresh: refresh button re-fetches list without switching view mode; live mode resumes poller, history mode keeps paused state.
- Instrumentation: console debug logs report cache hits/misses and hydration averages every few updates; metrics exposed on `window.AIReviewer.PrProgress.metrics`.
- Event details: timeline rows now expand in-place, showing key/value diagnostics with JSON fallback; toggles update `aria-expanded` and chevron states.
- Feature flag: disable Iteration 2 via JVM property `-Dai.reviewer.progress.iteration2.enabled=false` (or matching environment variable). UI automatically collapses to live-only mode and hides history controls when the flag is off.

#### Iteration 1 – Documentation & Verification

- [x] Document the floating overlay lifecycle (bootstrap, polling, auto-collapse) including the new summary badge behaviour.
- [x] Capture a manual smoke checklist covering the main states:
    1. Fresh PR with no AI review → panel renders waiting state, summary badge shows `Waiting`.
    2. Trigger review → verify live polling updates timeline, badges swap to `Running`, pause/resume buttons report status.
    3. Completion → confirm timeline auto-collapses with compact summary (`Completed`/`Failed` etc.) and manual toggle restores details.
    4. REST error simulation (`403` via admin permissions) → error banner announces failure and stops poller.
- [ ] Add automated UI coverage for the summary badge and collapse toggle (to be scheduled with Iteration 2 front-end work).

### Iteration 3 – Administrative Shortcuts & Notifications

- [ ] Surface "Force re-review" and "Delete history" actions (permission-gated, confirmed) that call the existing manual REST endpoints.
- [ ] Detect stalled progress (no new events for a configurable window) and display AUI toasts/flags; escalate failures with richer messaging.
- [ ] Allow drag/resize of the floating card, persisting the user’s preference locally.
- [ ] Optional: expose a dismissible inline variant for wide screens; fallback to floating card on narrow viewports.
- [ ] Work with Bitbucket UX to sanity-check the drag/resize affordances and ensure they align with Atlassian design guidelines.
- [ ] Decide on the permission model for the administrative actions (global admins only vs. project admins) and document in the release notes.
- [ ] Explore webhook or email escalation when a review remains stalled beyond the configured threshold (outside scope unless required, but good to track).
- [ ] Draft UI text for the admin action confirmation dialogs (force re-review, delete history) and run past content design.
- [ ] Model server-side audit logging for administrative actions to satisfy compliance reporting.
- [ ] Consider adding a lightweight scheduler to re-check stalled reviews post-fix and clear the warning badge automatically.

### Immediate Next Steps

- Draft API contract changes for `/progress/history` and share with backend reviewers.
- Prototype the dropdown UX flow (wireframe or Loom clip) to validate space constraints against the floating panel width.
- Spike the snapshot cache using sessionStorage to validate hydration timing and memory footprint before baking it into production code.
- Schedule a quick sync with QA to outline coverage expectations for the upcoming dropdown and admin actions enhancements.
- Capture metrics baseline (current polling response time, manual review start rates) before rolling out Iteration 2 so we can quantify the improvement.
- Align with release management on feature-flag strategy and planned rollout timeline.
- Create a tracking epic/subtasks in Jira so cross-functional teams can follow progress and contribute assets (docs, QA plans).
- Rough-in an E2E testing strategy (manual + automated) detailing which scenarios will be covered pre-launch vs. post-launch monitoring.
- Engage with support/CS teams to craft customer communication templates for the new UI behaviours.

## Supporting Tasks & Considerations

- Update REST rate limits/configuration docs once the live progress UI becomes prominent.
- Extend automated tests (frontend smoke + REST) to cover the new dropdown/actions and permission checks.
- Monitor polling performance after cache introduction; adjust TTLs if necessary.
- Coordinate release notes and provide troubleshooting guidance (e.g., common reasons the panel may be hidden).
- Document rollback steps for both frontend resources and backend toggles to ensure quick recovery if regressions appear.
- Create dashboards or log queries to monitor merge veto events once the in-progress guard ships to production.

## Risks & Mitigations

- **Large PRs exceed polling/render budgets** – throttle event payloads per request and paginate history before GA; monitor load testing results.
- **Feature flag left disabled in some environments** – track flag state in deployment runbooks and add startup log warnings when disabled.
- **Admin actions misused** – enforce permission checks, add audit trail, and document best practices for force re-review usage.
- **Caching inconsistencies** – maintain versioned snapshot schema and invalidate on mismatch to avoid showing stale timelines.

## Cross-Team Dependencies

- Platform analytics team for capturing dropdown usage metrics.
- Docs/content design for confirmation dialog copy and release notes.
- QA automation for adding coverage to existing Bitbucket UI regression suites.
- Support/CS for updating troubleshooting guides once the new UI is live.

## Milestones

1. **Iteration 1** released behind existing toggle.
2. **Iteration 2** gated by a secondary feature flag until drill-down stabilises.
3. **Iteration 3** adds admin operations and notifications; requires documentation update before rollout.

Progress updates will be tracked in this document as each checkbox is completed.
