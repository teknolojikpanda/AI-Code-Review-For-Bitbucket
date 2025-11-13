# Contributing to AI Code Reviewer for Bitbucket

## Development Environment Setup
1. **Install prerequisites**
   - JDK 8 (the plugin compiles against Java 8 via the `maven.compiler.source`/`target` settings).【F:pom.xml†L20-L47】
   - Atlassian SDK 8.2+ or a compatible Bitbucket development distribution. The SDK provides `atlas-run`, `atlas-debug`, and integration test commands.【F:pom.xml†L205-L248】
   - Maven 3.8+ and Git.
   - Optional: Node.js 16+ if you plan to extend JavaScript resources (current bundles are plain ES5 AUI scripts).
2. **Clone the repository**
   ```bash
   git clone https://bitbucket.org/<your-org>/ai-code-reviewer.git
   cd ai-code-reviewer
   ```
3. **Configure IDE**
   - Import as a Maven project (IntelliJ IDEA → *File → New → Project from Existing Sources*).
   - Enable annotation processing for Atlassian Spring Scanner components (IntelliJ: *Build, Execution, Deployment → Compiler → Annotation Processors*).
   - Install the Atlassian SDK or add the Bitbucket Server libraries to your IDE’s global libraries if you want code completion for Atlassian APIs.
   - Configure the Atlassian SDK Bitbucket home directory for faster `atlas-run` restarts (IntelliJ Atlassian Plugin optional).

## Running the Plugin Locally
1. **Start Bitbucket with the plugin**
   ```bash
   atlas-run
   ```
   - Downloads the Bitbucket distribution declared in `pom.xml`, installs the plugin, and starts Bitbucket on `http://localhost:7990/bitbucket` with automatic plugin reloading.
   - Use `atlas-debug` if you need remote debugging (defaults to port 5005).
2. **Run unit tests**
   ```bash
   atlas-mvn test
   ```
3. **Run integration tests**
   ```bash
   atlas-integration-test
   ```
   - Spins up Bitbucket in test mode and executes integration tests under `src/test/java`.
4. **Build the distribution JAR**
   ```bash
   atlas-mvn clean package
   ```
   - Artifacts are emitted to `target/ai-code-reviewer-<version>.jar`. Use `-DskipTests` only when absolutely necessary.
5. **Performance harness (optional)**
   ```bash
   atlas-mvn -Pperf clean verify
   ```
   - Runs the JMH harness located under `perf/` to validate chunking and guardrail performance regressions.【F:pom.xml†L292-L312】

## Project Structure
- `src/main/java/com/teknolojikpanda/bitbucket/aireviewer` — Bitbucket integration layer (event listeners, REST resources, servlets, services, guardrails, Active Objects models, DTOs).【F:src/main/java/com/teknolojikpanda/bitbucket/aireviewer/listener/PullRequestAIReviewListener.java†L23-L158】
- `src/main/java/com/teknolojikpanda/bitbucket/aicode` — AI orchestration core (diff providers, chunk planners, orchestrator, Ollama client, prompt cache).【F:src/main/java/com/teknolojikpanda/bitbucket/aicode/core/TwoPassReviewOrchestrator.java†L24-L147】
- `src/main/resources` — Atlassian plugin descriptor, Velocity templates, JS/CSS bundles, prompt templates, and i18n resources for admin and PR UIs.【F:src/main/resources/atlassian-plugin.xml†L11-L353】
- `src/test/java` — Unit and integration tests.
- `perf/` — JMH benchmark suite invoked via the `perf` Maven profile for regression testing of chunking throughput.【F:pom.xml†L292-L312】
- `docs/` — Supplemental documentation and product design artifacts (if present).

## Coding Guidelines
- **Java style**: Follow standard Atlassian Java conventions. Use SLF4J for logging (`LoggerFactory.getLogger`) and avoid `System.out` calls.【F:src/main/java/com/teknolojikpanda/bitbucket/aireviewer/service/AIReviewServiceImpl.java†L64-L111】
- **Spring Scanner**: Annotate components with `@Component`, `@Named`, `@ExportAsService`, and `@ComponentImport` as appropriate. Update `atlassian-plugin.xml` only when adding new extension points; Spring Scanner handles service registration automatically.【F:src/main/resources/atlassian-plugin.xml†L84-L220】
- **Bitbucket API usage**: Keep Bitbucket API calls inside service classes (`AIReviewServiceImpl`, `ReviewHistoryService`, etc.) and expose lightweight REST/servlet controllers. Use `SecurityService` for permission-sensitive operations.【F:src/main/java/com/teknolojikpanda/bitbucket/aireviewer/rest/ManualReviewResource.java†L44-L90】
- **Persistence**: Use Active Objects entities; avoid direct SQL. Add new entities to the `<ao>` section of `atlassian-plugin.xml` and generate schema upgrades via Active Objects upgrade tasks if column changes are needed.【F:src/main/resources/atlassian-plugin.xml†L14-L83】
- **Guardrails**: Reuse `ReviewConcurrencyController`, `ReviewRateLimiter`, `GuardrailsBurstCreditService`, and related services instead of re-implementing concurrency logic.【F:src/main/java/com/teknolojikpanda/bitbucket/aireviewer/service/ReviewConcurrencyController.java†L1-L200】
- **Frontend**: Place new JS/CSS in `src/main/resources/js`/`css`, register them in an appropriate `<web-resource>` context, and prefer AUI components for consistent styling.【F:src/main/resources/atlassian-plugin.xml†L226-L353】
- **Testing**: Cover new functionality with unit tests (`atlas-mvn test`) and, where applicable, integration tests that exercise Bitbucket APIs.

## Branching, Commits, and Pull Requests
- **Branching model**: `main` holds released code; create feature branches named `feature/<ticket>-<slug>` or `bugfix/<ticket>-<slug>`. Use `release/` branches when preparing production releases.
- **Commit messages**: Use imperative mood (`Add guardrail override REST API`). Reference issue IDs when integrating with Jira or similar trackers.
- **Pre-PR checklist**:
  - Run `atlas-mvn test` and fix failures.
  - Ensure new REST endpoints enforce permissions and include validation/error handling.
  - Update documentation (README, admin help, API docs) when behavior changes.
  - Provide screenshots for UI changes (attach to PR) and list guardrail impacts or rollout considerations.
- **Pull request expectations**: Describe testing performed, rollout/feature flag strategy, and any required configuration changes. Reviewers will verify tests, Active Objects schema updates, and security checks before approving.

## Release Process
1. Update the `<version>` in `pom.xml` and ensure compatibility metadata is up to date (e.g., supported Bitbucket version range in the properties section).【F:pom.xml†L60-L118】
2. Create or update release notes in `docs/` or your tracking system, highlighting new features, guardrail changes, and migration steps.
3. Build the release artifact:
   ```bash
   atlas-mvn clean package
   ```
4. Upload `target/ai-code-reviewer-<version>.jar` to your distribution channel (internal artifact repository or Marketplace listing). Follow your organization’s signing process if required.
5. Tag the release (`git tag vX.Y.Z && git push origin --tags`) and merge the release branch back to `main` (and `develop` if used).
6. Increment the project version to the next snapshot (`X.Y.(Z+1)-SNAPSHOT`) and document any post-release rollout steps (e.g., guardrail default changes, new configuration flags).
