# AI Code Reviewer for Bitbucket

Welcome to the documentation hub for **AI Code Reviewer for Bitbucket**, a Bitbucket Data Center plugin that runs automated pull request reviews with large language models and presents live feedback directly in the Bitbucket UI.

The plugin reacts to pull request events, orchestrates a guarded review pipeline backed by Ollama models, and exposes rich operational controls for administrators. Use this index to jump to guidance for reviewers, administrators, and contributors.

## Highlights

- **Automated and on-demand reviews** for new pull requests, updates, and administrator-triggered rechecks.
- **Two-pass AI analysis** that builds an overall summary and then posts inline findings with severity levels and remediation hints.
- **Live progress panel** inside the pull request that surfaces queue state, chunk status, and history.
- **Guardrails** for concurrency, queuing, rollouts, rate limits, and worker degradation to protect Bitbucket stability.
- **Administrative dashboards** for configuration, history browsing, health monitoring, and operational overrides.

## Audience Guide

| Audience | Recommended starting point |
| --- | --- |
| Reviewers, pull request authors | [User Guide](user-guide.md) |
| Bitbucket system administrators | [Admin Guide](admin-guide.md) |
| Architects and maintainers | [Architecture](architecture.md) |
| Plugin developers | [Developer Guide](developer-guide.md) and [Contributing](contributing.md) |
| Automation integrators | [REST API Reference](api/rest-api.md) |

## Documentation Map

- [User Guide](user-guide.md)
- [Admin Guide](admin-guide.md)
- [Architecture](architecture.md)
- [Developer Guide](developer-guide.md)
- [Configuration Reference](configuration-reference.md)
- [Troubleshooting](troubleshooting.md)
- [FAQ](faq.md)
- [REST API Reference](api/rest-api.md)
- [Contributing Guidelines](contributing.md)
- [Release Notes](release-notes.md)

## Compatibility

- **Bitbucket Server/Data Center**: built and tested against 9.6.5; requires Bitbucket 8.15 or later for the REST and guardrails APIs used by the plugin.
- **Java**: compiled for Java 8 to match Bitbucket’s supported runtime.
- **Model backend**: expects access to an Ollama instance reachable from every Bitbucket node.

See the [Release Notes](release-notes.md) for upgrade history and known changes between versions.
