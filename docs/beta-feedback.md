# Guardrails Beta Feedback Playbook

Use this guide to capture structured feedback from early adopters before enabling Guardrails for every project.

## 1. Announce the Beta

- Post in the relevant team rooms (Slack, Teams, email) describing what Guardrails protects and how to access the **AI Code Reviewer → Operations** dashboard.
- In-product reminder (Bitbucket banner):

```
Attention Guardrails beta testers: open Administration → AI Code Reviewer → Operations after your next PR review and submit feedback via go/guardrails-feedback by Friday.
```

## 2. Ask These Questions

| Topic | Sample Questions |
| --- | --- |
| Queue Experience | How often did reviews wait more than 2 minutes? Did the queue UI communicate why? |
| Limiter Defaults | Did burst credits or auto-snooze kick in when you expected them to? Were the defaults too strict/loose? |
| Telemetry | Which cards/tables were most useful? Anything confusing or missing? |
| Operational Tasks | Were rollout controls/burst credit CLI easy to use? Did you run into missing permissions? |
| Overall Confidence | Would you enable Guardrails for all repos today? If not, what blockers remain? |

Collect answers in a shared doc or form so trends are obvious.

## 3. Capture Objective Data

Ask testers to include the following (or collect centrally):

```bash
# Queue + limiter snapshot
curl -u $AUTH "$BASE/rest/ai-reviewer/1.0/monitoring/runtime" | \
  jq '.queue.activeRuns | length, .queue.waiting, .rateLimiter.recentThrottles'

# Alert summary
curl -u $AUTH "$BASE/rest/ai-reviewer/1.0/alerts" | jq '.alerts'

# CLI history
./scripts/guardrails-cli.sh burst-list --include-expired
```

## 4. Iterate on Defaults

1. **Queue settings** – If testers consistently report long waits, adjust `maxConcurrentReviews`/`maxQueuedReviews` in config and note the new default.
2. **Limiter thresholds** – Reduce/increase `repoRateLimitPerHour`/`projectRateLimitPerHour` or tweak alert percentages based on throttling complaints.
3. **Telemetry gaps** – Update the operator runbook if testers struggled to interpret rollout or limiter panels.

Document each change (who requested it, new value, why) in the release notes for transparency.

## 5. Close the Loop

- Share a summary back to the testers (what changed, remaining TODOs).
- Update the rollout ticket with survey stats + config adjustments.
- If blockers remain, schedule another beta wave before GA.
