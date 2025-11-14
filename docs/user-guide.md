# User Guide

This guide explains how reviewers and pull request authors interact with AI Code Reviewer inside Bitbucket.

## Where the Plugin Appears

- **Pull request page**: a floating “AI Review Progress” panel shows live status, queue position, chunk progress, and links to recent runs.
- **Pull request timeline**: inline comments authored by the configured AI reviewer account include severity badges, summaries, and remediation tips.
- **Overview tab**: an AI-authored summary comment captures high-level changes observed during the review.
- **Merge button**: if a review is still running, the *AI Review In-Progress* merge check blocks merging until the run finishes or is cancelled.

## Automatic Reviews

1. Create a pull request or push new commits to an existing one.
2. The plugin listener reacts to the event, verifies that AI reviews are enabled, and schedules a run.
3. The progress panel enters the **Queued** state. When worker capacity is available, the review moves to **Running** and processes chunks of the diff.
4. Findings are posted as threaded comments. The overall summary and inline findings are published as soon as each chunk completes.
5. When the review completes, the panel shows the final status (Success, Skipped, Failed) and the merge check clears.

Draft pull requests are skipped unless administrators enable “Review draft PRs” in the configuration.

## Live Progress Panel

The panel polls `/rest/ai-reviewer/1.0/progress` to surface the latest information:

- **Status badge**: Waiting, Queued, Running, Completed, Warning, or Failed.
- **Summary text**: short narrative of the current step (e.g., chunking changes, requesting model output, publishing comments).
- **Timeline**: expandable list of progress events, including queue entry, chunk processing, retries, and completion events.
- **History selector**: browse recent runs to compare outputs after updates.
- **Controls**:
  - **Pause / Resume updates** for accessibility or to reduce polling noise.
  - **Refresh** to pull the latest state immediately.
  - **Hide details** collapses the timeline for smaller screens.

If the panel does not appear, ensure AI reviews are enabled and you have permission to view the repository.

## Configuration Page

The **Configuration** page is available from the pull request sidebar and provides a read-only summary of the settings that affect the current repository:

- **Review status** shows whether automatic reviews are enabled and highlights guardrail reasons when reviews are skipped (such as repository allowlists, file type exclusions, or draft pull request enforcement).
- **Model profile** lists the AI provider, model name, temperature, and maximum tokens so you understand the reviewer’s behavior without leaving the pull request context.
- **Scope controls** display repository, project, and global overrides for branch filters, maximum diff size, and severity thresholds.

Use this page to quickly confirm whether administrators have customized behavior for a specific repository before escalating missing or unexpected findings.

## History Page

The **History** page surfaces the last 50 review runs for the pull request. Each entry includes:

- Execution time, duration, triggering user, and commit hash.
- Final status (Success, Skipped, Failed) with error details when available.
- Links to download raw AI responses for audit or escalation.

Select any run to open its timeline and compare findings against the current state. Use this page when you need to review how earlier iterations were evaluated or when analyzing regressions introduced by newer commits.

## Health Page

The **Health** page focuses on service reliability. It provides:

- Current connectivity to the configured AI provider, including last successful call and response latency.
- Queue depth metrics for scheduled reviews and worker availability.
- Recent error rates grouped by failure type (provider outage, guardrail rejection, internal exception).

Open the Health page if progress appears stalled or reviews are delayed. It helps determine whether the issue is localized to your pull request or due to a wider service degradation that administrators must address.

## Operations Page

The **Operations** page offers quick actions for reviewers and project maintainers with the required permission:

- **Trigger manual review** reruns the AI analysis immediately, even when automatic reviews are disabled.
- **Cancel running review** stops the in-flight job and clears merge checks.
- **Purge cached data** clears stored AI responses for the pull request to reclaim space or remove sensitive information.
- **Download logs** bundles request/response metadata (excluding code content) to attach to support tickets.

Use these tools when coordinating with administrators, responding to compliance requests, or debugging why a review produced unexpected findings.

## Reading AI Findings

- **Inline comments** include severity (Critical, High, Medium, Low) and a description of the issue. They may also provide suggested code snippets.
- **Summary comment** outlines the overall risk, affected files, and recommended actions.
- Comments are posted by the service user configured in the global settings; mention or reply to that account as you would to a human reviewer.
- When issues are resolved or outdated, resolve the comment threads as usual to keep the PR tidy.

## Requesting a Fresh Review

- Push a new commit: the listener automatically reschedules a differential review of the new changes.
- Ask a system administrator to trigger a manual run via the Operations page or the `/rest/ai-reviewer/1.0/history/manual` endpoint.

## Best Practices

- Keep pull requests under the configured size limits (files, diff size, chunk count) to avoid guardrail throttling.
- Respond to AI findings promptly; the merge check can be configured to require human approval for higher severities.
- Use the history selector to compare findings between iterations and confirm that fixes addressed previously raised issues.
- If you suspect a false positive, leave a comment explaining why so administrators can refine prompts or severity filters.

For detailed configuration and advanced operations see the [Admin Guide](admin-guide.md) and [Configuration Reference](configuration-reference.md).
