# AI Code Reviewer for Bitbucket

AI Code Reviewer for Bitbucket is a Bitbucket Server/Data Center plugin that runs automated pull request reviews using large language models hosted by Ollama. It posts inline findings, summaries, and live progress updates while enforcing guardrails that keep review throughput predictable.

## Features

- Automatic reviews triggered on pull request creation and updates.
- Two-pass orchestration with summaries plus inline findings and severity tagging.
- Merge check that prevents merges while AI analysis is running.
- Administrative dashboards for configuration, history, health, and operations.
- Guardrails covering concurrency, rate limits, burst credits, rollout cohorts, and alerting.
- Extensive REST API for automation and monitoring.

## Documentation

Comprehensive documentation lives in the [`docs/`](docs/) directory:

- [Overview](docs/index.md)
- [User Guide](docs/user-guide.md)
- [Admin Guide](docs/admin-guide.md)
- [Architecture](docs/architecture.md)
- [Developer Guide](docs/developer-guide.md)
- [Configuration Reference](docs/configuration-reference.md)
- [REST API Reference](docs/api/rest-api.md)
- [Troubleshooting](docs/troubleshooting.md) and [FAQ](docs/faq.md)
- [Contributing](docs/contributing.md) and [Release Notes](docs/release-notes.md)

## Building

```bash
JAVA_HOME=/path/to/jdk-17 mvn clean package
```

The build requires JDK 17 to match the Bitbucket 9.6.5 runtime. The command produces `target/ai-code-reviewer-<version>.jar`, which can be uploaded to Bitbucket via the Universal Plugin Manager. For development instances, use the Atlassian SDK:

```bash
atlas-run --product bitbucket --version 9.6.5 --plugins target/ai-code-reviewer-*.jar
```

## Support

For issues or feature requests, open a ticket in the project tracker and include Bitbucket version, plugin version, relevant logs, and configuration snapshots. Security reports should be sent privately to the maintainer team.
