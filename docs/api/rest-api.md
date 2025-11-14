# REST API Reference

All endpoints are served from the plugin’s REST module at `/rest/ai-reviewer/1.0`. Unless stated otherwise, responses use JSON and require authentication. Permission checks are enforced by each resource.

## Authentication & Permissions

- **System administrators**: required for configuration, automation, scheduler control, manual reviews, and guardrail management.
- **Project/Repository users**: can query live progress and history for repositories where they have read access.
- Requests exceeding rate limits receive HTTP 429 with `Retry-After` headers when applicable.

## Alerts (`/alerts`)

| Method | Path | Description | Permissions |
| --- | --- | --- | --- |
| GET | `/alerts` | Evaluate guardrail conditions and return the current alert snapshot. Also triggers alert delivery if thresholds are met. | System administrator |

## Automation (`/automation`)

| Method | Path | Description | Permissions |
| --- | --- | --- | --- |
| POST | `/automation/rollout/{mode}` | Change scheduler mode (`active`, `pause`, or `drain`). Optional JSON body `{ "reason": "..." }`. | System administrator |
| GET | `/automation/rollout/state` | Retrieve current scheduler mode, actor, and timestamp. | System administrator |
| GET | `/automation/rollout/cohorts` | List rollout cohorts and telemetry for staged enablement. | System administrator |
| POST | `/automation/rollout/cohorts` | Create a cohort (`key`, `displayName`, `scopeMode`, `projectKey`, `repositorySlug`, `rolloutPercent`, `darkFeatureKey`, `enabled`). | System administrator |
| PUT | `/automation/rollout/cohorts/{id}` | Update an existing cohort. | System administrator |
| DELETE | `/automation/rollout/cohorts/{id}` | Remove a cohort. | System administrator |
| GET | `/automation/channels` | Paginated list of alert channels with delivery aggregates. Supports `limit`/`offset`. | System administrator |
| POST | `/automation/channels` | Create a channel (`url`, `description`, `enabled`, `signRequests`, `secret`, retry options). | System administrator |
| PUT | `/automation/channels/{id}` | Update channel metadata, toggle enablement, rotate secrets. | System administrator |
| DELETE | `/automation/channels/{id}` | Delete a channel. | System administrator |
| POST | `/automation/channels/{id}/test` | Send a test alert to validate channel configuration. | System administrator |
| GET | `/automation/alerts/deliveries` | Paginated delivery history with acknowledgement statistics. | System administrator |
| POST | `/automation/alerts/deliveries/{id}/ack` | Acknowledge a delivery. Optional payload `{ "note": "..." }`. | System administrator |
| GET | `/automation/burst-credits` | List active (and optional expired) burst credits. Query `includeExpired=true` to include expired records. | System administrator |
| POST | `/automation/burst-credits` | Grant burst credits (`scope`, identifiers, `tokens`, `durationMinutes`, `reason`, `note`). | System administrator |
| DELETE | `/automation/burst-credits/{id}` | Revoke an existing burst credit. Optional payload `{ "note": "..." }`. | System administrator |

## Configuration (`/config`)

| Method | Path | Description | Permissions |
| --- | --- | --- | --- |
| GET | `/config` | Retrieve the effective global configuration, defaults, limiter snapshot, and repository overrides. | System administrator |
| PUT | `/config` | Update configuration keys. Payload is a JSON map of key/value pairs (see [Configuration Reference](../configuration-reference.md)). | System administrator |
| GET | `/config/limiter` | Rate limiter snapshot including overrides and recent incidents. | System administrator |
| POST | `/config/limiter/overrides` | Create or update a rate limit override (scope, identifier, limit, expiry, reason). | System administrator |
| PUT | `/config/limiter/overrides/{id}` | Modify an existing override. | System administrator |
| DELETE | `/config/limiter/overrides/{id}` | Remove an override before it expires. | System administrator |
| GET | `/config/repository-catalog` | List projects and repositories for selection UIs. | System administrator |
| GET | `/config/users` | Search Bitbucket users (`q`, `limit`, `offset`). | System administrator |
| GET | `/config/scope` | Summarise scope mode and selected repositories. | System administrator |
| POST | `/config/scope` | Replace scope definitions (allow/block lists). | System administrator |
| POST | `/config/test-connection` | Test Ollama connectivity using current or supplied URL/model values. | System administrator |
| POST | `/config/auto-approve` | Toggle the `autoApprove` setting. Payload `{ "enabled": true|false }`. | System administrator |

## Repository Configuration (`/config/repositories`)

| Method | Path | Description | Permissions |
| --- | --- | --- | --- |
| GET | `/config/repositories/{projectKey}/{repositorySlug}` | Fetch repository override. | System administrator or repository admin |
| GET | `/config/repositories/id/{repositoryId}` | Fetch override by repository ID. | System administrator or repository admin |
| PUT | `/config/repositories/{projectKey}/{repositorySlug}` | Replace override; payload is a partial configuration map. | System administrator or repository admin |
| PUT | `/config/repositories/id/{repositoryId}` | Replace override by repository ID. | System administrator or repository admin |
| DELETE | `/config/repositories/{projectKey}/{repositorySlug}` | Remove override and inherit global defaults. | System administrator or repository admin |
| DELETE | `/config/repositories/id/{repositoryId}` | Remove override by repository ID. | System administrator or repository admin |

## Progress (`/progress`)

| Method | Path | Description | Permissions |
| --- | --- | --- | --- |
| GET | `/progress/{projectKey}/{repositorySlug}/{pullRequestId}` | Live progress snapshot for an in-flight review. Returns 404 if none running. | Repository read access |
| GET | `/progress/{projectKey}/{repositorySlug}/{pullRequestId}/history` | Paginated list of recent runs for a PR (`limit`, `offset`). | Repository read access |
| GET | `/progress/history/{historyId}` | Detailed timeline and metrics for a completed review. | Repository read access |
| GET | `/progress/admin/scheduler/state` | Scheduler mode and metadata. | System administrator |
| GET | `/progress/admin/queue` | Current queue contents and worker utilisation. | System administrator |
| POST | `/progress/admin/scheduler/pause` | Pause new reviews. | System administrator |
| POST | `/progress/admin/scheduler/drain` | Allow running reviews to finish and stop scheduling new work. | System administrator |
| POST | `/progress/admin/scheduler/resume` | Resume normal scheduling. | System administrator |
| POST | `/progress/admin/queue/cancel` | Cancel a queued review (payload includes history ID or PR reference). | System administrator |
| POST | `/progress/admin/queue/cancel/bulk` | Cancel multiple queued reviews. | System administrator |
| POST | `/progress/admin/running/cancel` | Cancel a running review. | System administrator |
| POST | `/progress/admin/running/cancel/bulk` | Cancel multiple running reviews. | System administrator |

## Review History (`/history`)

| Method | Path | Description | Permissions |
| --- | --- | --- | --- |
| GET | `/history` | Paginated history records with filters for project, repository, status, manual flag, and date. | System administrator |
| GET | `/history/metrics` | Aggregated review metrics (counts, durations). | System administrator |
| GET | `/history/metrics/daily` | Daily metrics breakdown. | System administrator |
| POST | `/history/backfill/chunks` | Request backfill of chunk telemetry for historical entries. | System administrator |
| GET | `/history/cleanup/status` | Status of the cleanup scheduler. | System administrator |
| POST | `/history/cleanup` | Trigger cleanup run; payload can include retention values or `dryRun`. | System administrator |
| GET | `/history/cleanup/export` | Start export of cleanup results. | System administrator |
| GET | `/history/cleanup/export/download` | Download the most recent cleanup export archive. | System administrator |
| POST | `/history/cleanup/integrity` | Run integrity checks across history tables. | System administrator |
| GET | `/history/cleanup/integrity` | Retrieve the latest integrity check results. | System administrator |
| GET | `/history/{id}` | Fetch a single history record. | Repository read access |
| GET | `/history/{id}/chunks` | Return chunk-level telemetry for the history record. | Repository read access |

## Manual Reviews (`/history/manual`)

| Method | Path | Description | Permissions |
| --- | --- | --- | --- |
| POST | `/history/manual` | Trigger a manual review. Payload: `projectKey`, `repositorySlug`, `pullRequestId`, optional `force`, `treatAsUpdate`. | System administrator |

## Metrics (`/metrics`)

| Method | Path | Description | Permissions |
| --- | --- | --- | --- |
| GET | `/metrics` | Operational metrics including worker utilisation, queue depths, and circuit breaker state. | System administrator |

## Monitoring (`/monitoring`)

| Method | Path | Description | Permissions |
| --- | --- | --- | --- |
| GET | `/monitoring/runtime` | Lightweight runtime summary (scheduler status, queue stats, worker heartbeat). | System administrator or monitoring service account |

## Notes

- All endpoints return standard HTTP status codes with an `error` field when applicable.
- Rate limits apply per user and per endpoint, as enforced by each resource’s `RateLimiter` helper.
- IDs returned by list endpoints (cohorts, channels, deliveries, burst credits, history records) are opaque and may change between environments.

See the [Configuration Reference](../configuration-reference.md) for configuration keys and the [Troubleshooting Guide](../troubleshooting.md) for operational tips.
