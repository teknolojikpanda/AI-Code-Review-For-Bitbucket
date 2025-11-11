package com.teknolojikpanda.bitbucket.aireviewer.service;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Evaluates guardrail telemetry and emits human-readable alerts that can be surfaced via REST/UI or external monitoring.
 */
@Named
@Singleton
public class GuardrailsAlertingService {

    private static final long CLEANUP_STALE_THRESHOLD_MS = 24 * 60 * 60 * 1000L; // 24 hours
    private final GuardrailsTelemetryService telemetryService;

    @Inject
    public GuardrailsAlertingService(GuardrailsTelemetryService telemetryService) {
        this.telemetryService = Objects.requireNonNull(telemetryService, "telemetryService");
    }

    public AlertSnapshot evaluateAlerts() {
        Map<String, Object> runtime = telemetryService.collectRuntimeSnapshot();
        List<Map<String, Object>> alerts = new ArrayList<>();

        Map<String, Object> queue = asMap(runtime.get("queue"));
        Map<String, Object> retention = asMap(runtime.get("retention"));
        Map<String, Object> schedule = asMap(retention.get("schedule"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> recentRuns = retention.containsKey("recentRuns")
                ? (List<Map<String, Object>>) retention.get("recentRuns")
                : Collections.emptyList();

        evaluateQueueAlerts(queue, alerts);
        evaluateRetentionAlerts(schedule, recentRuns, alerts);

        AlertSnapshot snapshot = new AlertSnapshot();
        snapshot.generatedAt = System.currentTimeMillis();
        snapshot.runtime = runtime;
        snapshot.alerts = alerts;
        return snapshot;
    }

    private void evaluateQueueAlerts(Map<String, Object> queue, List<Map<String, Object>> alerts) {
        if (queue.isEmpty()) {
            return;
        }
        Number maxConcurrent = toNumber(queue.get("maxConcurrent"));
        Number active = toNumber(queue.get("active"));
        Number waiting = toNumber(queue.get("waiting"));
        int available = (maxConcurrent != null && active != null)
                ? Math.max(0, maxConcurrent.intValue() - active.intValue())
                : -1;
        if (available == 0 && waiting != null && waiting.intValue() > 0) {
            alerts.add(alert("critical",
                    "AI review queue saturated",
                    "No review slots available and " + waiting.intValue() + " reviews waiting.",
                    "Pause low-priority repositories or raise the concurrent limit temporarily."));
        }
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> repoWaiters = (List<Map<String, Object>>) queue.get("repoWaiters");
        if (repoWaiters != null && !repoWaiters.isEmpty()) {
            alerts.add(alert("warning",
                    "Repository queue cap reached",
                    repoWaiters.size() + " repositories are hitting per-repo queue limits.",
                    "Consider raising per-repo queue caps for critical repos or pausing noisy ones."));
        }
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> projectWaiters = (List<Map<String, Object>>) queue.get("projectWaiters");
        if (projectWaiters != null && !projectWaiters.isEmpty()) {
            alerts.add(alert("warning",
                    "Project queue cap reached",
                    projectWaiters.size() + " projects are at their queue limits.",
                    "Increase per-project queue caps or spread reviews across projects."));
        }
        Map<String, Object> schedulerState = asMap(queue.get("schedulerState"));
        String mode = schedulerState.containsKey("mode")
                ? Objects.toString(schedulerState.get("mode"), "ACTIVE")
                : "ACTIVE";
        if (!"ACTIVE".equalsIgnoreCase(mode)) {
            alerts.add(alert("info",
                    "Scheduler is " + mode.toLowerCase(),
                    "AI review scheduler mode is " + mode + ".",
                    "Resume the scheduler from the admin Guardrails panel when ready."));
        }
    }

    private void evaluateRetentionAlerts(Map<String, Object> schedule,
                                         List<Map<String, Object>> recentRuns,
                                         List<Map<String, Object>> alerts) {
        if (schedule.isEmpty()) {
            return;
        }
        boolean enabled = Boolean.TRUE.equals(schedule.get("enabled"));
        if (!enabled) {
            alerts.add(alert("warning",
                    "Cleanup disabled",
                    "Automatic history cleanup is disabled.",
                    "Enable cleanup to keep AI review history within the retention window."));
        }
        Object lastError = schedule.get("lastError");
        if (lastError != null && !Objects.toString(lastError, "").isEmpty()) {
            alerts.add(alert("critical",
                    "Cleanup job failing",
                    "Last cleanup run failed: " + lastError,
                    "Investigate the cleanup error and re-run manually once resolved."));
        }
        Long lastRun = toLong(schedule.get("lastRun"));
        if (enabled && (lastRun == null || isOlderThanThreshold(lastRun))) {
            alerts.add(alert("warning",
                    "Cleanup stale",
                    "Cleanup has not run in the past 24 hours.",
                    "Trigger a manual cleanup from the Health dashboard or inspect the scheduler state."));
        }
        int consecutiveFailures = countRecentFailures(recentRuns, 3);
        if (consecutiveFailures >= 3) {
            alerts.add(alert("critical",
                    "Repeated cleanup failures",
                    "Last " + consecutiveFailures + " cleanup attempts failed.",
                    "Review cleanup logs and database health before rerunning."));
        }
    }

    private boolean isOlderThanThreshold(long lastRun) {
        return System.currentTimeMillis() - lastRun > CLEANUP_STALE_THRESHOLD_MS;
    }

    private int countRecentFailures(List<Map<String, Object>> runs, int sample) {
        if (runs == null || runs.isEmpty()) {
            return 0;
        }
        int failures = 0;
        for (int i = 0; i < runs.size() && i < sample; i++) {
            Map<String, Object> run = runs.get(i);
            boolean success = Boolean.TRUE.equals(run.get("success"));
            if (!success) {
                failures++;
            }
        }
        return failures;
    }

    private Map<String, Object> alert(String severity, String summary, String detail, String recommendation) {
        Map<String, Object> alert = new LinkedHashMap<>();
        alert.put("severity", severity);
        alert.put("summary", summary);
        alert.put("detail", detail);
        alert.put("recommendation", recommendation);
        alert.put("generatedAt", System.currentTimeMillis());
        return alert;
    }

    private Map<String, Object> asMap(Object value) {
        if (value instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> map = (Map<String, Object>) value;
            return map;
        }
        return Collections.emptyMap();
    }

    private Number toNumber(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number) {
            return (Number) value;
        }
        if (value instanceof String) {
            try {
                return Double.parseDouble((String) value);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private Long toLong(Object value) {
        Number number = toNumber(value);
        return number != null ? number.longValue() : null;
    }

    public static class AlertSnapshot {
        private long generatedAt;
        private Map<String, Object> runtime;
        private List<Map<String, Object>> alerts;

        public long getGeneratedAt() {
            return generatedAt;
        }

        public Map<String, Object> getRuntime() {
            return runtime;
        }

        public List<Map<String, Object>> getAlerts() {
            return alerts;
        }
    }
}
