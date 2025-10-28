# Sourcery Alignment Plan

## Vision
Replicate Sourcery AI’s automated pull-request review workflow inside Bitbucket using an Ollama-hosted model. The plugin should deliver structured findings, actionable fixes, approval guidance, and configurable guardrails comparable to Sourcery’s experience.

## Current State Assessment
- **Diff acquisition:** `DefaultDiffProvider` rebuilds per-file diffs and tracks per-file stats; CRLF handling recently fixed inside `HeuristicChunkPlanner`.
- **Chunk planning:** Single-heuristic planner allocates files until char/file thresholds but needs richer logic (semantic grouping, prioritisation, retry support).
- **Prompting & AI orchestration:** Two-pass flow exists, but prompts are static; no differentiation by change risk level or historical context.
- **Finding validation:** `AIReviewServiceImpl` filters issues with basic line presence checks; lacks semantic validation (e.g., verifying path exists, severity policy).
- **PR feedback:** Comments posted with minimal templating, no per-issue remediation guidance or suppression handling.
- **Config / UX:** Admin UI exposes raw numeric fields; no profiles, presets, or workflow toggles; REST lacks guardrails for invalid payloads.
- **Metrics & telemetry:** Metrics recorded but not surfaced; no persistence of AI call outcomes, retries, or response quality metrics.

## Target Capabilities
1. **Smart diff management**
   - Detect binary/large files, fallback to summaries.
   - Prioritise risky files (auth/security) earlier.
   - Support incremental reviews (re-review changed chunks).
2. **Adaptive AI prompting**
   - Contextual system prompts (security, performance).
   - Provide code context slices (surrounding lines) like Sourcery.
   - Capture model rationale & confidence scores.
3. **Finding validation & triage**
   - Enhanced diff verification (exact added lines).
   - Duplicate detection & suppression by signature.
   - Map severities to Bitbucket reviewer actions (block, warn).
4. **Comment & summary UX**
   - Rich templates with action items, reproduction steps.
   - Summary comment with checklist (pass/fail by category).
   - Optional auto-approval when only low findings remain.
5. **Configuration & integrations**
   - Review profiles (security-only, full review, lightweight).
   - Default profiles shipped; ability to tune prompts per profile.
   - Web UI improvements (validation, descriptions, presets).
6. **Observability**
   - Persist call metrics, failures, and AI payload size.
   - Provide admin endpoint/dashboard for review history.

## Roadmap
### Phase 0 – Foundations (current sprint)
1. Finalise diff/CRLF handling & ensure all files reach planner.
2. Document architecture & create plan/progress tracking (this file + progress log).
3. Stabilise existing pipeline (error handling, sanitisation, retries).

### Phase 1 – Capability Gap Closure
1. **Diff & chunking enhancements**
   - Introduce `ChunkStrategy` abstraction (e.g., risk-based, size-based).
   - Track original file metadata (binary, language, directories).
2. **Prompt & response improvements**
   - Parameterise prompts with config-defined segments.
   - Include surrounding context lines & commit metadata.
   - Capture model rationale string for UI display.
3. **Finding validation**
   - Add diff-position resolver that maps unified diff to file version.
   - Compute issue fingerprints (path + line + summary hash).
   - Maintain per-PR issue store to avoid duplicate comments on re-run.

### Phase 2 – UX & Workflow
1. Build templated comment renderer (Markdown partials).
2. Create summary generator with category counts & approval decision.
3. Implement review profiles & expose in Admin UI with descriptions.
4. Add REST endpoints for reviewing history & toggling auto-approve.

### Phase 3 – Observability & Polish
1. Persist metrics in AO (response times, token usage when available).
2. Provide admin dashboard (REST + AUI) for insights & retries.
3. Harden configuration validation (profiles, prompts).
4. Expand tests (integration for diff mapping, orchestrator).

## Dependencies & Risks
- Ollama model quality—may need support for multiple models or external fallback (OpenAI/Sourcery-like).
- Performance of sequential per-file diff streaming—consider caching.
- Bitbucket API rate limits when posting many comments.
- Need to ensure backward compatibility with existing installations.

## Next Actions
1. Capture baseline metrics & behaviour (current logs, diff stats).
2. Create progress log (`SOURCERY_ALIGNMENT_PROGRESS.md`) and update per milestone.
3. Begin Phase 1 backlog refinement (define interfaces, plan refactor steps).

