# Configuration Reference

This reference lists the configuration keys managed by `AIReviewerConfigService`. Global settings apply to all repositories unless a repository override supplies a different value.

- **Scope**: `Global` settings live under **Administration → AI Code Reviewer → Configuration**. `Repository` overrides are available via the overrides table or REST (`/rest/ai-reviewer/1.0/config/repositories`).
- **Type**: `Bool`, `Int`, or `String`. Prompt templates accept multi-line strings.
- All numerical values are validated within the ranges enforced in `AIReviewerConfigServiceImpl`.

## Connection & Models

| Key | Scope | Type | Default | Description |
| --- | --- | --- | --- | --- |
| `ollamaUrl` | Global/Repo | String | `http://0.0.0.0:11434` | Base URL of the Ollama instance queried for reviews. |
| `ollamaModel` | Global/Repo | String | `qwen3-coder:30b` | Primary model used for chunk analysis. |
| `fallbackModel` | Global/Repo | String | `qwen3-coder:7b` | Model used when health checks or retries fail on the primary. Must differ from `ollamaModel`. |
| `connectTimeout` | Global/Repo | Int (ms) | 10000 | HTTP connect timeout for Ollama requests. |
| `readTimeout` | Global/Repo | Int (ms) | 30000 | HTTP read timeout for Ollama responses. |
| `ollamaTimeout` | Global/Repo | Int (ms) | 300000 | Maximum time allowed for a single Ollama review request. |
| `apiDelayMs` | Global/Repo | Int (ms) | 100 | Artificial delay between API calls to avoid overload. |

## Review Behaviour

| Key | Scope | Type | Default | Description |
| --- | --- | --- | --- | --- |
| `enabled` | Global/Repo | Bool | `true` | Master switch for automated AI reviews. |
| `reviewDraftPRs` | Global/Repo | Bool | `false` | Whether draft pull requests are reviewed. |
| `skipGeneratedFiles` | Global/Repo | Bool | `true` | Skip files matching generated artefact patterns. |
| `skipTests` | Global/Repo | Bool | `false` | Skip files identified as tests (matching keywords). |
| `autoApprove` | Global/Repo | Bool | `false` | Automatically approve PRs when no issues meet approval thresholds. |
| `minSeverity` | Global/Repo | String | `medium` | Lowest severity (`low`, `medium`, `high`, `critical`) that is surfaced. |
| `requireApprovalFor` | Global/Repo | String CSV | `critical,high` | Severities that require at least one human approval. |
| `reviewProfile` | Global/Repo | String | `balanced` | Key of a preset defined in `ReviewProfilePreset`. |
| `reviewExtensions` | Global/Repo | String CSV | `java,groovy,js,ts,tsx,jsx,py,go,rs,cpp,c,cs,php,rb,kt,swift,scala` | File extensions included in reviews. |
| `ignorePatterns` | Global/Repo | String CSV | `*.min.js,*.generated.*,package-lock.json,yarn.lock,*.map` | Glob patterns excluded from analysis. |
| `ignorePaths` | Global/Repo | String CSV | `node_modules/,vendor/,build/,dist/,.git/` | Directory prefixes to ignore. |
| `aiReviewerUser` | Global/Repo | String | *(empty)* | Optional Bitbucket username used to author AI comments. If blank, the triggering user is impersonated. |
| `workerDegradationEnabled` | Global | Bool | `true` | Allow worker pool to throttle itself when saturation persists. |

## Chunking & Retries

| Key | Scope | Type | Default | Description |
| --- | --- | --- | --- | --- |
| `maxCharsPerChunk` | Global/Repo | Int | 60000 | Maximum characters per diff chunk sent to the model. |
| `maxFilesPerChunk` | Global/Repo | Int | 3 | Maximum files grouped into a chunk. |
| `maxChunks` | Global/Repo | Int | 20 | Cap on number of chunks per review. |
| `parallelThreads` | Global/Repo | Int | 4 | Number of worker threads used to process chunks concurrently. |
| `maxParallelChunks` | Global/Repo | Int | 4 | Upper bound on simultaneous chunk requests per review. |
| `maxDiffSize` | Global/Repo | Int (bytes) | 10000000 | Reviews exceeding this diff size are skipped. |
| `maxIssuesPerFile` | Global/Repo | Int | 50 | Maximum AI findings recorded per file. |
| `maxIssueComments` | Global/Repo | Int | 30 | Maximum AI comments posted per review. |
| `maxRetries` | Global/Repo | Int | 3 | Overall retry attempts for failed chunk processing. |
| `chunkMaxRetries` | Global/Repo | Int | 3 | Per-chunk retry limit. |
| `overviewMaxRetries` | Global/Repo | Int | 2 | Retry attempts for the overview stage. |
| `baseRetryDelay` | Global/Repo | Int (ms) | 1000 | Base delay between retries. |
| `chunkRetryDelay` | Global/Repo | Int (ms) | 1000 | Delay between chunk retries. |
| `overviewRetryDelay` | Global/Repo | Int (ms) | 1500 | Delay between overview retries. |

## Capacity & Rate Limits

| Key | Scope | Type | Default | Description |
| --- | --- | --- | --- | --- |
| `maxConcurrentReviews` | Global | Int | 2 | Maximum active reviews cluster-wide. |
| `maxQueuedReviews` | Global | Int | 25 | Maximum queued reviews across the cluster. |
| `maxQueuedPerRepo` | Global | Int | 5 | Queue cap per repository. |
| `maxQueuedPerProject` | Global | Int | 15 | Queue cap per project. |
| `repoRateLimitPerHour` | Global | Int | 12 | Automatic review limit per repository per hour. |
| `projectRateLimitPerHour` | Global | Int | 60 | Automatic review limit per project per hour. |
| `priorityProjects` | Global | String CSV | *(empty)* | Project keys that receive priority handling. |
| `priorityRepositories` | Global | String CSV | *(empty)* | `PROJECT/slug` identifiers for priority repositories. |
| `priorityRateLimitSnoozeMinutes` | Global | Int | 30 | Minutes to snooze rate limits after a priority override. |
| `priorityRepoRateLimitPerHour` | Global | Int | 24 | Elevated hourly limit for priority repositories. |
| `priorityProjectRateLimitPerHour` | Global | Int | 120 | Elevated hourly limit for priority projects. |
| `repoRateLimitAlertPercent` | Global | Int (%) | 80 | Utilisation percentage that triggers alerts for repositories. |
| `projectRateLimitAlertPercent` | Global | Int (%) | 80 | Utilisation percentage that triggers alerts for projects. |
| `repoRateLimitAlertOverrides` | Global | String | *(empty)* | Optional JSON/CSV overrides for repository alert thresholds. |
| `projectRateLimitAlertOverrides` | Global | String | *(empty)* | Optional overrides for project alert thresholds. |

## Scope Control

| Key | Scope | Type | Default | Description |
| --- | --- | --- | --- | --- |
| `scopeMode` | Global | String | `all` | Determines enforcement of the scope allow/block list (`all`, `allow_list`, `block_list`). |

Repository overrides implicitly define scope membership when `scopeMode` is not `all`.

## Prompt Customisation

Keys beginning with `prompt` (for example `prompt.system`, `prompt.overview`, `prompt.chunk`) accept multi-line strings to override the bundled templates stored in `src/main/resources/prompts`. They are validated for size and for unsafe placeholders before saving.

## Derived Metadata

- `aiReviewerUserDisplayName` is computed by the service when returning configuration and cannot be set manually.

## Configuration Export

Use `GET /rest/ai-reviewer/1.0/config` to retrieve the effective global configuration. Repository overrides can be listed with `GET /rest/ai-reviewer/1.0/config/repositories`.

When applying updates via REST, send JSON documents containing only the keys you wish to change; unspecified keys keep their previous values.
