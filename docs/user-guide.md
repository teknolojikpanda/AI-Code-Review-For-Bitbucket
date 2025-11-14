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
