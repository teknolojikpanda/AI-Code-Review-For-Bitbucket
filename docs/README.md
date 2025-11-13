# Guardrails Documentation Hub

Welcome to the AI Code Reviewer Guardrails doc set. This folder contains everything needed for developers, operators, and beta testers to understand how the plugin is assembled, how it runs inside Bitbucket Data Center, and how to extend or operate it safely.

## Quick Navigation

| Audience | Start Here | Description |
| --- | --- | --- |
| Architects / Contributors | [architecture.md](architecture.md) | Component diagrams, request flows, data stores. |
| Plugin Developers | [contributor-guide.md](contributor-guide.md) | Dev environment setup, build/test scripts, coding conventions. |
| Bitbucket Admins | [operator-runbook.md](operator-runbook.md) | Day‑to‑day operations, telemetry panels, incident playbooks. |
| Beta Program Leads | [beta-feedback.md](beta-feedback.md) | Survey questions + data to collect during staged rollout. |
| QA / Release Owners | [regression-checklist.md](regression-checklist.md), [perf-test-plan.md](perf-test-plan.md) | Pre-GA validation (functional + performance). |
| End Users | [user-guide.md](user-guide.md) | How reviewers trigger AI analysis, interpret results, and view progress. |

### Related Scripts

- `scripts/guardrails-cli.sh` – Administrative CLI (scheduler, scope, cleanup, burst credits).
- `scripts/guardrails-rollout.sh` – One-command rollout/rollback wrapper around the CLI.
- `perf/load-test.py` – Synthetic load generator to stress monitoring + scheduler endpoints.

### Directory Structure Recap

```
src/
  main/java/com/teknolojikpanda/bitbucket/aireviewer     # REST resources, services
  main/java/com/teknolojikpanda/bitbucket/aicode         # Core AI orchestration + heuristics
  main/resources/templates                               # Velocity templates (admin UI)
  main/resources/js                                      # Admin/ops dashboards
docs/                                                    # This documentation hub
scripts/                                                 # Operational CLIs
```

Keep the README updated whenever a new doc is added so future contributors can find the relevant guide in seconds.
