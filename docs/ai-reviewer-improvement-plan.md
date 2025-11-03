# AI Reviewer Improvement Plan

## Configuration & Scope
- [x] Implement incremental loading and caching for the repository scope tree (admin JS) with clear loading/error states.
- [x] Refine `synchronizeRepositoryOverrides` so repository overrides store deltas instead of cloning the entire global configuration.
- [x] Extend config REST endpoints with pagination, stricter payload validation, and rate limiting safeguards.

## Review Pipeline
- Restore a privileged manual-review trigger (REST/UI) that bypasses listener gating for admin force-runs.
- Refactor diff collection to stream incrementally, reducing memory pressure for large pull requests.

## History & Metrics
- Add database indexes for history queries (`REVIEW_START_TIME`, project/repo) to keep admin views responsive.
- Capture richer state in review history (source/target commits, PR version) for accurate idempotency checks.

## Error Handling & Observability
- Enrich failure summaries in review results/comments with exception class/details to aid troubleshooting.
- Improve admin UI error surfacing by exposing response status/text and logging correlation data.

## Testing & Quality
- Add permission boundary tests for `RepoConfigResource` ensuring repo/project-admin scenarios are enforced.
- Introduce performance/load tests around chunk planning and diff streaming for large PRs.
- Expand i18n coverage by moving hard-coded UI strings into `ai-code-reviewer.properties`.

## Front-End Maintainability
- Break up large admin JS/CSS bundles into smaller modules to simplify maintenance and enable future bundling optimisations.
