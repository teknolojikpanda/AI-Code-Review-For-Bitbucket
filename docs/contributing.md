# Contributing

We welcome improvements to AI Code Reviewer. This guide documents the expected workflow, coding standards, and review practices.

## Getting Started

1. **Fork and clone** the repository.
2. Install **Java 8**, **Maven 3.8+**, and the **Atlassian SDK** (`atlas-version` ≥ 8.2).
3. Run `mvn clean install` once to download dependencies and build the plugin.
4. Configure your IDE (IntelliJ IDEA recommended) with the Lombok plugin disabled—this project does not use Lombok and relies on explicit getters/setters in Active Objects entities.

## Branching Strategy

- Create feature branches from `main` following the pattern `feature/<summary>` or `bugfix/<ticket>`.
- Keep branches focused; avoid bundling unrelated changes.
- Rebase onto the latest `main` before opening a pull request to reduce merge conflicts.

## Commit & PR Guidelines

- Use conventional, descriptive commit messages (e.g., `fix: handle empty prompt overrides`).
- Include documentation updates alongside feature changes when behaviour or configuration varies.
- Every pull request should describe:
  - Motivation and summary of changes.
  - Testing performed (commands, results).
  - Impact on deployment or operations, if any.
- Request review from at least one maintainer familiar with Bitbucket plugin development.

## Code Style

- Follow Atlassian’s Java style: spaces over tabs, 100-character line length target, meaningful variable names.
- Use `Objects.requireNonNull` for constructor parameter validation.
- Prefer immutable DTOs (`ReviewResult`, `ReviewIssue`) and avoid public mutable fields.
- REST responses should use `LinkedHashMap` to guarantee field order where UI relies on it.
- Do not suppress warnings without a clear justification in a comment.

## Testing & Quality Gates

- Unit tests: `mvn test`.
- Static analysis: enable `mvn -P lint` if a profile is provided (add additional linters as the project evolves).
- Integration tests: `atlas-integration-test` (optional but encouraged when touching REST or AO persistence).
- Manual verification: run `atlas-run` and exercise the admin UI plus a sample pull request.

## Documentation Expectations

- Update relevant Markdown files under `docs/` when adding features, endpoints, or configuration keys.
- Regenerate REST API documentation snippets if endpoints change (see [REST API Reference](api/rest-api.md)).
- Maintain a clear changelog entry under [Release Notes](release-notes.md).

## Review Process

- Reviews happen via Bitbucket pull requests.
- Address review comments promptly; prefer follow-up commits over force-pushing unless rebasing.
- Include screenshots when modifying UI elements, especially admin pages or the PR panel.
- Merge only after CI passes and at least one approval is recorded.

## Release Process

1. Ensure `release-notes.md` is up to date and version bumps are reflected in `pom.xml`.
2. Run `mvn clean package` to produce the distributable JAR.
3. Tag the release (`git tag vX.Y.Z`), push the tag, and upload the artefact to the internal distribution channel.
4. Notify administrators to install via UPM and monitor logs for schema upgrades.

## Reporting Issues

- File issues in the project tracker with reproduction steps, logs, Bitbucket version, and configuration snapshots (`/rest/ai-reviewer/1.0/config`).
- For security concerns, contact the maintainers via the private security mailing list rather than opening a public ticket.

Thank you for contributing!
