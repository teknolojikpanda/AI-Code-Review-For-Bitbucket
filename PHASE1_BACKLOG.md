# Phase 1 Backlog – Capability Gap Closure

This backlog elaborates the Phase 1 items from the Sourcery Alignment Plan and breaks them into actionable engineering tasks. Each workstream is scoped for iterative delivery (1–2 day slices) so we can demonstrate value while de-risking the larger refactors.

## 1. Diff & Chunking Enhancements

| ID | Task | Description | Deliverables | Dependencies |
|----|------|-------------|--------------|--------------|
| C1 | Define `ChunkStrategy` interface | Introduce SPI so planners can swap heuristics (size-based, risk-based). | New interface + default implementation migration notes. | Existing `HeuristicChunkPlanner`, metrics wiring. |
| C2 | Migrate planner to strategy composition | Refactor planner to delegate to configured `ChunkStrategy`; keep current behaviour behind `SizeFirstStrategy`. | Updated planner, unit coverage for strategy selection. | Task C1. |
| C3 | Risk metadata extractor | Capture file metadata (language, directory, churn) to influence strategies. | Utility class + tests, wiring into diff collection. | `DefaultDiffProvider`, repository services. |
| C4 | Security-priority strategy prototype | Strategy that prioritises security/auth-related files using metadata. | Strategy implementation + configuration flag. | Tasks C1, C3. |
| C5 | Incremental review scaffolding | Persist last reviewed revision per file so we can skip unchanged hunks. | Data model update + minimal orchestration hook. | Active Objects schema (Phase 3) |

## 2. Prompt & Response Improvements

| ID | Task | Description | Deliverables | Dependencies |
|----|------|-------------|--------------|--------------|
| P1 | Prompt template parametrisation | Move hard-coded prompt text into configurable template segments (system, overview, instructions). | Template files, loader, config wiring. | `OllamaAiReviewClient`. |
| P2 | Contextual prompt builder | Inject language-specific context (e.g., surrounding lines, commit metadata). | Builder class + tests, integrates with chunk review flow. | Task P1. |
| P3 | Model rationale capture | Extend response schema to request `rationale`/`confidence`. | Schema update, parsing changes, validation. | `OllamaAiReviewClient` schema enforcement. |
| P4 | Configurable prompt profiles | Allow prompts per review profile (security-only vs full). | Config model + admin UI hooks (Phase 2). | Task P1, configuration service. |

## 3. Finding Validation & Triage

| ID | Task | Description | Deliverables | Dependencies |
|----|------|-------------|--------------|--------------|
| F1 | Diff position resolver | Translate unified diff positions to final file coordinates to validate issues robustly. | Resolver component + tests, integrates with `isValidIssue`. | Diff metadata from C1/C2. |
| F2 | Issue fingerprinting | Generate deterministic hash (path + line window + summary) to detect duplicates. | Fingerprinting utility + store. | Task F1. |
| F3 | Duplicate suppression store | Persist fingerprints between runs to avoid re-posting same comment. | AO entity/table + service. | Task F2, DB migrations. |
| F4 | Severity policy mapper | Map severity levels to PR actions (block, warn). | Policy config + enforcement in review orchestration. | Config service updates. |

### Backlog Notes

- 2025-10-28: ✅ C1 complete — `ChunkStrategy` interface added to API, unblocking strategy refactor (C2).
- 2025-10-28: ✅ C2 complete — `HeuristicChunkPlanner` now delegates to the `SizeFirstChunkStrategy` implementation.
- 2025-10-28: ✅ C3 complete — File metadata extractor captures language, directory, and churn for strategy inputs.
- 2025-10-28: ✅ P1 complete — Prompt templates externalised and configurable per AI review.
- 2025-10-28: ✅ P2 complete — Chunk instructions now include per-file metadata context derived from collected diff data.
- 2025-10-28: ✅ F1 complete — Diff position resolver validates issue line numbers against parsed hunks.
- 2025-10-28: ✅ F2 complete — Issue fingerprinting prevents duplicate AI findings within a review run.
- 2025-10-28: 📄 F3 migration plan prepared (see `docs/duplicate_fingerprint_migration_plan.md`).
- Tasks tagged with schema or database updates (C5, F3) require coordination with Phase 3 observability work; we will spike designs now but defer migrations until the data model story is ready.
- Prompt profile UI wiring (P4) is blocked on Phase 2 admin improvements but backend structures can land earlier behind feature flags.
- We should aim to complete C1–C3 and P1–P2 before tackling duplicate suppression to ensure the AI findings we store are already validated reliably.
