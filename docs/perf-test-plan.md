# Guardrails Load/Perf Test Plan

This plan ensures Guardrails can withstand worst-case review volumes before GA. Always run these steps in a staging environment that mirrors production topology.

## 1. Test Inputs

- **Base URL**: `https://stash-stage.example.com`
- **Auth**: Bot/service account with system-admin rights (used for CLI + REST).
- **Duration**: 10 minutes per scenario (extend if you need longer soak periods).
- **Concurrency**: 4–8 threads to mimic multiple admins/automation hooks.

## 2. Tooling

- `scripts/guardrails-cli.sh` for state changes (pause/drain/resume, scope, burst credits).
- `scripts/guardrails-rollout.sh` to flip guardrails on/off quickly during scenarios.
- `perf/load-test.py` to generate monitoring/API load and stress the scheduler endpoints.
- Optional traffic: fire a handful of manual AI reviews (small PRs) to observe queue throughput while the load script runs.

## 3. Scenarios

### Scenario A – Scheduler Flip Storm
1. Run `python perf/load-test.py --base-url $BASE --auth $AUTH --duration 600 --concurrency 6`.
2. Observe `/rest/ai-reviewer/1.0/automation/rollout/state` to confirm the scheduler can flip pause/resume rapidly without entering an inconsistent state.
3. Queue 3 manual reviews mid-test; ensure they complete once the script resumes scheduling.

### Scenario B – Burst Alert/Audit Traffic
1. Create temporary alert channels (webhook receiver) and use `guardrails-cli.sh alerts` every minute while the load script runs.
2. Verify `/rest/ai-reviewer/1.0/automation/alerts/deliveries` and associated audit tables do not back up.

### Scenario C – Rate-Limiter Pressure
1. Grant several burst credits using `guardrails-cli.sh burst-grant` while the load script runs.
2. Confirm `GET /rest/ai-reviewer/1.0/monitoring/runtime` shows limiter+burst metrics updating under load.

## 4. Monitoring & Metrics

During the tests collect:

- `curl $BASE/rest/ai-reviewer/1.0/metrics | jq '.runtime.queue, .runtime.rateLimiter'` every ~2 minutes.
- JVM/system metrics from BB nodes (CPU, heap, GC) if available.
- Guardrails plugin logs (`tail -f <bitbucket-home>/log/atlassian-bitbucket.log | grep [Guardrails]`).

## 5. Pass/Fail Criteria

- No HTTP 5xx responses from Guardrails endpoints during the run.
- Queue length returns to baseline (< 3) once load completes.
- Scheduler state transitions remain consistent (final state equals last flip performed).
- Alert deliveries and audit tables do not emit error logs.
- No AO constraint violations or thread-pool exhaustion errors in Bitbucket logs.

## 6. Reporting

- Capture the summary output from `perf/load-test.py`.
- Attach metrics snapshots/charts and CLI outputs to the rollout ticket.
- Document any anomalies (latency spikes, throttling storms) and remediation steps before GA.
