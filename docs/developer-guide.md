# Developer Guide

This guide walks contributors through the project layout, build tooling, and internal coding conventions.

## Project Structure

```
ai-code-reviewer/
├── pom.xml                         # Maven build, Atlassian SDK configuration
├── src/main/java/
│   └── com/teknolojikpanda/bitbucket/
│       ├── aireviewer/             # Bitbucket integration, services, REST, AO entities
│       │   ├── ao/                 # Active Objects entities for configuration & telemetry
│       │   ├── hook/               # Merge checks
│       │   ├── listener/           # Pull request event listeners
│       │   ├── progress/           # Live progress registry & events
│       │   ├── rest/               # REST resources under /rest/ai-reviewer/1.0
│       │   ├── service/            # Business logic, guardrails, schedulers
│       │   └── util/               # Shared helpers (logging, metrics, HTTP client, rate limiting)
│       └── aicode/                 # AI orchestration (diff provider, chunk planner, client, models)
├── src/main/resources/
│   ├── atlassian-plugin.xml        # Module declarations
│   ├── templates/                  # Velocity templates for admin pages
│   ├── js/                         # AMD modules for admin UI and PR progress panel
│   ├── css/                        # Styling for admin/PR panels
│   └── prompts/                    # Prompt templates consumed by the orchestrator
└── src/test/java/…                 # (Add tests alongside production packages)
```

## Build and Run

- Use Java 8 and Maven 3.8+.
- `mvn clean package` produces `target/ai-code-reviewer-${version}.jar` for distribution.
- To start a Bitbucket development instance with the plugin:
  1. Install the Atlassian SDK (`atlas-version` ≥ 8.2).
  2. Run `atlas-run --product bitbucket --version 9.6.5 --plugins target/ai-code-reviewer-*.jar`.
  3. Bitbucket boots with the plugin enabled; log in as `admin:admin` and navigate to **Administration → AI Code Reviewer**.
- Use `atlas-debug` to attach a remote debugger and `atlas-integration-test` for integration testing if needed.

## Coding Conventions

- Favour constructor injection with `@Inject` and `@ComponentImport` for Bitbucket services.
- Use `LogSupport`/`LogContext` for structured logging; avoid direct SLF4J calls unless necessary for external interfaces.
- Keep REST resources immutable and stateless; reuse helper methods to centralise permission checks.
- Guard external calls (Ollama, HTTP) with `CircuitBreaker` and retry utilities already provided.
- Store new persistent state via Active Objects entities under `aireviewer.ao`; run `atlas-mvn datamodel:generate` if you add or modify AO fields.

## Adding Features

### New REST Endpoint

1. Create a class in `aireviewer.rest` with `@Path` and `@Named` annotations.
2. Inject `UserManager`, `PermissionService`, and any required services via constructor.
3. Follow the `Access` helper pattern in `ProgressResource` to validate permissions.
4. Register routes with appropriate HTTP verbs and media types.
5. Update [`docs/api/rest-api.md`](api/rest-api.md) with the new endpoint.

### New Admin UI Surface

1. Add a Velocity template under `src/main/resources/templates` and any supporting JS/CSS assets.
2. Declare a `<web-resource>` in `atlassian-plugin.xml` to load assets and a `<web-item>` or `<servlet>` for navigation.
3. Use AUI components and AMD modules (`define([...], function(...) { ... })`) consistent with existing scripts.
4. Ensure REST endpoints backing the UI enforce permission checks.

### Extending the Review Pipeline

- Modify or extend classes under `aicode.core` for prompt generation, chunk planning, or AI client behaviour.
- Preserve the two-pass contract: overview first, then chunks. Use `ReviewConfigFactory` to expose new configuration options.
- Record metrics via `MetricsRecorder` so the progress and history screens reflect new states.

### Updating Guardrails

- Adjust `ReviewConcurrencyController`, `ReviewRateLimiter`, or `GuardrailsRateLimitStore` to change queue behaviour.
- Surface new operator controls through `AutomationResource` and Operations UI components.
- Update Active Objects schemas if new telemetry needs persistence.

## Testing

- Unit tests belong next to the code under `src/test/java` (use JUnit 5 where possible).
- Mock Bitbucket services with Atlassian’s test harness or Mockito; avoid hitting external services.
- For end-to-end validation, rely on `atlas-integration-test` with embedded Bitbucket.

## Internationalisation and Accessibility

- Admin templates rely on Velocity and AUI; follow Atlassian accessibility guidelines.
- Strings destined for UI should be externalised if localisation is required in the future.
- Ensure progress panel updates respect ARIA attributes (`aria-live`, `aria-expanded`) as demonstrated in `ai-reviewer-pr-progress.js`.

For contribution logistics (branching, review process, coding standards) read the [Contributing Guide](contributing.md).
