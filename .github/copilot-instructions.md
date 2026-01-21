# Copilot instructions for AI Code Reviewer (Bitbucket)

## Project context
- This repo is a Bitbucket Server/Data Center plugin. Core Java code lives under `src/main/java/com/teknolojikpanda/bitbucket` with two primary packages:
  - `aireviewer/` = Bitbucket integration (listeners, REST, services, AO entities, UI resources).
  - `aicode/` = AI review pipeline (diff provider, chunk planning, AI client, findings).
- Review flow: `PullRequestAIReviewListener` → `AIReviewServiceImpl` → `TwoPassReviewOrchestrator` (overview then chunk passes) → comment posting + history persistence. Keep the two-pass contract intact when modifying pipeline classes.
- Ollama is the external dependency; see `OllamaAiReviewClient` and config keys like `ollamaUrl` in `AIReviewerConfigServiceImpl` (documented in `docs/configuration-reference.md`).

## Architecture & persistence
- Active Objects entities live in `src/main/java/.../aireviewer/ao`. If you add fields, update the AO schema and run `atlas-mvn datamodel:generate`.
- Guardrails and queueing are centralized in `ReviewConcurrencyController`, `ReviewRateLimiter`, and `GuardrailsRateLimitStore`. Changes often require updates in REST/admin surfaces.
- Live progress is tracked in `ProgressRegistry` and exposed via REST; merge checks use `AIReviewInProgressMergeCheck`.

## REST, UI, and configuration patterns
- REST endpoints are under `/rest/ai-reviewer/1.0` (`aireviewer/rest`). Use the `Access` helper pattern from `ProgressResource`/`AutomationResource` for permission checks.
- Admin UI uses Velocity templates in `src/main/resources/templates` and AMD JS modules in `src/main/resources/js`. Register assets in `src/main/resources/atlassian-plugin.xml`.
- Prompt templates are in `src/main/resources/prompts`; configuration keys use `prompt.*` with placeholders like `{{CHUNK_CONTEXT}}` (see `docs/configuration-reference.md`).

## Build & test workflow
- Build with Java 17 + Maven: `mvn clean package` (produces `target/ai-code-reviewer-<version>.jar`).
- Dev run uses Atlassian SDK: `atlas-run --product bitbucket --version 9.6.5 --plugins target/ai-code-reviewer-*.jar`.
- Tests live under `src/test/java` and use JUnit 5 + Mockito; prefer Bitbucket test harness for service mocks.

## Logging & metrics conventions
- Use `LogSupport`/`LogContext` for structured logs and MDC context; avoid ad-hoc SLF4J calls.
- Metrics should go through `MetricsCollector` / `MetricsRecorderAdapter` so admin dashboards and history remain consistent.
