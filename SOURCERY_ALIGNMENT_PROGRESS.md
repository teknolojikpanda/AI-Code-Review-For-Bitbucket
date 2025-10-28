# Sourcery Alignment Progress

## 2025-10-27

- Created baseline git checkpoint (`chore: checkpoint before sourcery ai alignment`).
- Drafted alignment plan outlining current gaps, target capabilities, and phased roadmap.
- Confirmed CRLF diff handling fix in `HeuristicChunkPlanner` to ensure full file coverage prior to enhancements.
- Added toggleable diagnostics (`ai.reviewer.diagnostics=true`) capturing raw diffs, per-file diffs, and chunk compositions to aid diff/partition debugging.
- Instrumented planner with additional diagnostics for candidate files and extracted per-file hunks to chase missing-in-chunk issues surfaced in PR #24.
- Updated Ollama client to treat repeated chat failures (timeouts, JSON parse errors, missing fallback model) as empty results instead of aborting the chunk, and log a concise root cause. This fixes the “Chunk chunk-0 failed: All model invocations failed” regression when the fallback model is absent.
- Added resilience around Ollama `EOF` failures: chunk payloads are automatically truncated (20k chars) and retried when the server closes connections mid-stream, and repeated “model not found” responses are cached to avoid hammering unavailable models.

## Next Steps

- Begin designing chunk strategy abstraction and enhanced prompting pipeline.

## 2025-10-28

- Committed AI comment anchoring change to place feedback on the closing line when multiline ranges are reported (Bitbucket rendering improvement).
- Drafted Phase 1 backlog (`PHASE1_BACKLOG.md`) detailing actionable tasks for chunking strategies, prompt templates, and finding validation.
- Updated alignment plan to reference the new backlog so status can be tracked per workstream.
- Introduced the `ChunkStrategy` interface (Phase 1 / C1) to decouple chunk planning heuristics from the current `HeuristicChunkPlanner`.

## Next Steps

- Prioritise backlog items C2–C3 and P1–P2 for implementation spikes.
- Define acceptance criteria and test coverage strategy for concrete `ChunkStrategy` implementations.
