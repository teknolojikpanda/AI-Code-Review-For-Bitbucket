# Frequently Asked Questions

## Does the plugin support Bitbucket Server and Data Center?
Yes. The plugin is developed against Bitbucket Data Center 9.6.5 and requires Bitbucket 8.15 or newer. It runs on single-node Server installations and clustered deployments.

## Which AI models are supported?
The built-in client targets Ollama models. You can configure any model available on your Ollama host by setting `ollamaModel` and an optional `fallbackModel` in the configuration.

## Can I limit reviews to specific repositories?
Yes. Set `scopeMode` to `allow_list` or `block_list` in the global configuration, then manage repository overrides via the Configuration page or the `/rest/ai-reviewer/1.0/config/repositories` endpoints.

## How do I trigger a manual review?
System administrators can trigger reviews from the Operations console or send a POST request to `/rest/ai-reviewer/1.0/history/manual` with the project key, repository slug, and pull request ID. Manual runs honour guardrails unless `force` is set to `true`.

## Where are review results stored?
All review history, chunk metadata, guardrail incidents, and configuration snapshots live in Bitbucket’s Active Objects tables (`AI_REVIEW_*` and `GUARDRAILS_*`). They are automatically created during plugin installation.

## Can I customise the prompts sent to the model?
Yes. Provide values for keys starting with `prompt` (for example `prompt.system`, `prompt.overview`, `prompt.chunk`). They replace the bundled templates in `src/main/resources/prompts`. Validation rejects overly large or unsafe prompt values.

## What happens if the AI backend is down?
The plugin retries according to the configured retry policy, may switch to the fallback model, and raises guardrail alerts. Administrators can pause the scheduler from the Operations console to prevent repeated failures until the backend recovers.

## Does the plugin require additional Bitbucket licences?
No. The plugin uses standard Bitbucket APIs and runs under the existing Bitbucket licence. The configured service user must be a regular Bitbucket account with permission to comment on pull requests.

## How do I remove AI comments from a pull request?
Delete or resolve the comments directly in the pull request. If you need to clean history, use the cleanup tools under **Administration → AI Code Reviewer → Review History** or call `/rest/ai-reviewer/1.0/history/cleanup`.

## How do upgrades affect existing configuration?
Upgrades preserve existing Active Objects data. Schema migrations run automatically at startup. Always back up the database before upgrading and review the [Release Notes](release-notes.md) for version-specific steps.
