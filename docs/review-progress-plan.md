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

- [ ] Add a "Recent Runs" dropdown within the panel that lists the last N history entries and loads their timelines via `GET /progress/history/{historyId}`.
- [ ] Enable per-event expansion to reveal chunk-level diagnostics (model, attempts, duration, failure reason) sourced from the history REST payload.
- [ ] Cache the most recent snapshot per PR so the overlay renders immediately on revisit without waiting for the first poll.

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

## Supporting Tasks & Considerations

- Update REST rate limits/configuration docs once the live progress UI becomes prominent.
- Extend automated tests (frontend smoke + REST) to cover the new dropdown/actions and permission checks.
- Monitor polling performance after cache introduction; adjust TTLs if necessary.
- Coordinate release notes and provide troubleshooting guidance (e.g., common reasons the panel may be hidden).

## Milestones

1. **Iteration 1** released behind existing toggle.
2. **Iteration 2** gated by a secondary feature flag until drill-down stabilises.
3. **Iteration 3** adds admin operations and notifications; requires documentation update before rollout.

Progress updates will be tracked in this document as each checkbox is completed.
