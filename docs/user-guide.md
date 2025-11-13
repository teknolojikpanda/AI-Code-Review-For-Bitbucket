# Guardrails User Guide

This guide targets Bitbucket users (developers/reviewers) who interact with the AI Code Reviewer Guardrails features. It explains how to trigger AI reviews, interpret queue/progress status, and handle common scenarios.

## 1. Enabling AI Reviews

1. Ensure your Bitbucket admin has configured Guardrails (scope, feature flags) via **Administration → AI Code Reviewer → Configuration**.
2. When Guardrails are active for your repository:
   - Pull requests automatically queue AI reviews (subject to rate limits and queue caps).
   - Manual reviews are available from the PR “AI Review” panel (look for “Start AI Review” or “Re-run AI Review” buttons).

## 2. Checking Review Status

- **PR Sidebar**: The AI Review panel shows whether the review is queued, running, or completed. Use the “View Progress” link to open the detailed drawer.
- **Progress Drawer**: Displays event timeline (`review.started`, `analysis.started`, `review.completed`, etc.), ETA, and any throttling/skip reasons (e.g., “Generated file skipped”).
- **Operations Dashboard**: (Admins) “Review Queue” card shows all running/queued reviews, their repo/PR, and why they might be deferred (rollout mode, scope pressure).

## 3. Interpreting Guardrails Behaviour

| Scenario | What You’ll See | What to Do |
| --- | --- | --- |
| Queue delay | Status stays “Queued” and Operations dashboard shows high waiting count. | Ask an admin to check queue caps or pause low-priority repos. |
| Rate-limiter throttle | Progress drawer shows `review.throttled` with retry ETA. | Wait for the limiter to reset or request a burst credit from admins. |
| Generated/test files skipped | Progress drawer details include `generated` or `tests` counters. | Adjust repository settings if you need those files reviewed (admin change). |
| Guardrails shadow mode | Operations dashboard shows “Shadow” lozenges for active runs. | Feedback goes to admins; results do not block your PR, but telemetry is collected. |

## 4. Manual Actions

- **Cancel AI Review**: Admins can cancel queued/running reviews from the Operations dashboard. If you no longer need the review, ping an admin instead of re-running repeatedly.
- **Burst Credit Requests**: If you have a critical deadline, contact the platform team to grant temporary burst credits via `guardrails-cli.sh burst-grant`.

## 5. Troubleshooting Checklist

1. **PR shows “Review failed” immediately** – Check the event timeline; might be due to empty diff or misconfigured scope.
2. **AI comments look outdated** – Use “Re-run AI Review” (this respects queue + limiter rules) or verify the PR commit changed.
3. **Model output degraded** – Platform team can consult `ModelHealthService` telemetry and, if needed, fail over to the fallback model.

## 6. Feedback Loop

During beta/rollouts, share your experience using the survey questions outlined in [docs/beta-feedback.md](beta-feedback.md). Key items to report:
- How long reviews took to start/finish.
- Whether the progress UI made sense.
- Any repos/files that should be excluded/included by default.

Armed with this guide, everyday Bitbucket users should feel confident requesting AI reviews, reading the resulting feedback, and knowing when to involve the platform team for assistance.
