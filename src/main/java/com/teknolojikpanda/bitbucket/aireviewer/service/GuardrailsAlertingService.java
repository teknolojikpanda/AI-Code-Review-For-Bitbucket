package com.teknolojikpanda.bitbucket.aireviewer.service;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * Evaluates guardrail telemetry and emits human-readable alerts that can be surfaced via REST/UI or external monitoring.
 */
@Named
@Singleton
public class GuardrailsAlertingService {

    private static final long CLEANUP_STALE_THRESHOLD_MS = 24 * 60 * 60 * 1000L; // 24 hours
    private static final double BREAKER_WARNING_RATIO = 0.10d;
    private static final double BREAKER_CRITICAL_RATIO = 0.25d;
    private static final double BREAKER_BLOCKED_WARNING = 1d;
    private static final double BREAKER_BLOCKED_CRITICAL = 5d;
    private final GuardrailsTelemetryService telemetryService;
    private final GuardrailsAlertChannelService channelService;
    private final AIReviewerConfigService configService;

    @Inject
    public GuardrailsAlertingService(GuardrailsTelemetryService telemetryService,
                                     GuardrailsAlertChannelService channelService,
                                     AIReviewerConfigService configService) {
        this.telemetryService = Objects.requireNonNull(telemetryService, "telemetryService");
        this.channelService = Objects.requireNonNull(channelService, "channelService");
        this.configService = Objects.requireNonNull(configService, "configService");
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
        Map<String, Object> rateLimiter = asMap(runtime.get("rateLimiter"));
        evaluateRateLimiterAlerts(rateLimiter, alerts);
        Map<String, Object> circuitBreaker = asMap(runtime.get("circuitBreaker"));
        evaluateBreakerAlerts(circuitBreaker, alerts);

        return new AlertSnapshot(System.currentTimeMillis(), runtime, alerts);
    }

    public AlertSnapshot evaluateAndNotify() {
        AlertSnapshot snapshot = evaluateAlerts();
        if (!snapshot.getAlerts().isEmpty()) {
            channelService.notifyChannels(snapshot);
        }
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

    void evaluateRateLimiterAlerts(Map<String, Object> rateLimiter,
                                   List<Map<String, Object>> alerts) {
        if (rateLimiter.isEmpty()) {
            return;
        }
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> repoBuckets = asList(rateLimiter.get("topRepoBuckets"));
        evaluateRateLimiterBuckets(repoBuckets, true, alerts);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> projectBuckets = asList(rateLimiter.get("topProjectBuckets"));
        evaluateRateLimiterBuckets(projectBuckets, false, alerts);
    }

    void evaluateRateLimiterBuckets(List<Map<String, Object>> buckets,
                                    boolean repository,
                                    List<Map<String, Object>> alerts) {
        if (buckets == null || buckets.isEmpty()) {
            return;
        }
        int inspected = 0;
        for (Map<String, Object> bucket : buckets) {
            if (inspected++ >= 5) {
                break;
            }
            String scope = Objects.toString(bucket.get("scope"), null);
            Number limit = toNumber(bucket.get("limit"));
            Number consumed = toNumber(bucket.get("consumed"));
            if (scope == null || limit == null || limit.doubleValue() <= 0 || consumed == null) {
                continue;
            }
            int threshold = repository
                    ? configService.resolveRepoRateLimitAlertPercent(scope)
                    : configService.resolveProjectRateLimitAlertPercent(scope);
            if (threshold <= 0) {
                continue;
            }
            double usagePercent = (consumed.doubleValue() / limit.doubleValue()) * 100d;
            if (usagePercent < threshold) {
                continue;
            }
            Number remaining = toNumber(bucket.get("remaining"));
            Number resetInMs = toNumber(bucket.get("resetInMs"));
            String summary = repository
                    ? "Repository rate limit nearing capacity"
                    : "Project rate limit nearing capacity";
            StringBuilder detail = new StringBuilder();
            detail.append(scopeLabel(scope, repository))
                    .append(" has consumed ")
                    .append(Math.round(usagePercent))
                    .append("% (")
                    .append(consumed.intValue())
                    .append("/")
                    .append(limit.intValue())
                    .append(") of its hourly budget");
            if (remaining != null) {
                detail.append(", ").append(Math.max(0, remaining.intValue())).append(" tokens remaining");
            }
            detail.append(".");
            if (resetInMs != null) {
                detail.append(" Resets in ").append(formatDuration(resetInMs.longValue())).append(".");
            }
            String recommendation = repository
                    ? "Grant burst credits or raise the repository rate limit if this is expected."
                    : "Consider increasing the project rate limit or staggering AI requests.";
            alerts.add(alert("warning", summary, detail.toString(), recommendation));
        }
    }

    void evaluateBreakerAlerts(Map<String, Object> circuit,
                               List<Map<String, Object>> alerts) {
        if (circuit == null || circuit.isEmpty()) {
            return;
        }
        Number openRatio = toNumber(circuit.get("openSampleRatio"));
        if (openRatio != null) {
            double ratio = openRatio.doubleValue();
            if (ratio >= BREAKER_CRITICAL_RATIO) {
                alerts.add(alert("critical",
                        "AI vendor circuit breaker is open",
                        "Breaker spent " + Math.round(ratio * 100) + "% of recent samples in OPEN state.",
                        "Fail over to the fallback model or pause reviews until the vendor recovers."));
            } else if (ratio >= BREAKER_WARNING_RATIO) {
                alerts.add(alert("warning",
                        "AI vendor circuit breaker flapping",
                        "Breaker open ratio is " + Math.round(ratio * 100) + "% over the recent window.",
                        "Investigate vendor health before the breaker locks open."));
            }
        }
        Number blockedAvg = toNumber(circuit.get("avgBlockedCallsPerSample"));
        if (blockedAvg != null) {
            double avg = blockedAvg.doubleValue();
            if (avg >= BREAKER_BLOCKED_CRITICAL) {
                alerts.add(alert("critical",
                        "Breaker blocking AI calls",
                        "Blocked calls per sample sits at " + String.format(Locale.ROOT, "%.1f", avg) + ".",
                        "Reduce concurrency or switch models until block pressure subsides."));
            } else if (avg >= BREAKER_BLOCKED_WARNING) {
                alerts.add(alert("warning",
                        "Breaker starting to block AI calls",
                        "Average blocked calls per sample is " + String.format(Locale.ROOT, "%.1f", avg) + ".",
                        "Keep an eye on model latency and consider granting burst credits sparingly."));
            }
        }
        Number clientHardFailures = toNumber(circuit.get("clientHardFailures"));
        if (clientHardFailures != null && clientHardFailures.longValue() > 0) {
            alerts.add(alert("info",
                    "AI client reported hard failures",
                    clientHardFailures.longValue() + " unrecoverable model calls were recorded.",
                    "Check AI vendor status or recent release changes affecting prompts."));
        }
    }

    private String scopeLabel(String scope, boolean repository) {
        if (scope == null || scope.isEmpty()) {
            return repository ? "Repository" : "Project";
        }
        return repository
                ? "Repository '" + scope + "'"
                : "Project '" + scope.toUpperCase(Locale.ROOT) + "'";
    }

    private String formatDuration(long millis) {
        if (millis <= 0) {
            return "seconds";
        }
        long minutes = TimeUnit.MILLISECONDS.toMinutes(millis);
        if (minutes >= 1) {
            return minutes + "m";
        }
        long seconds = Math.max(1, TimeUnit.MILLISECONDS.toSeconds(millis));
        return seconds + "s";
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

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> asList(Object value) {
        if (value instanceof List) {
            return (List<Map<String, Object>>) value;
        }
        return Collections.emptyList();
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
        private final long generatedAt;
        private final Map<String, Object> runtime;
        private final List<Map<String, Object>> alerts;

        public AlertSnapshot(long generatedAt,
                             Map<String, Object> runtime,
                             List<Map<String, Object>> alerts) {
            this.generatedAt = generatedAt;
            this.runtime = runtime != null ? runtime : Collections.emptyMap();
            this.alerts = alerts != null ? alerts : Collections.emptyList();
        }

        public long getGeneratedAt() {
            return generatedAt;
        }

        public Map<String, Object> getRuntime() {
            return runtime;
        }

        public List<Map<String, Object>> getAlerts() {
            return alerts;
        }

        public static AlertSnapshot sample(String description) {
            Map<String, Object> runtime = Map.of(
                    "type", "guardrails-test",
                    "description", description != null ? description : "Guardrails webhook test");
            Map<String, Object> alert = Map.of(
                    "severity", "info",
                    "summary", "Guardrails webhook test",
                    "detail", "This is a test notification to verify the AI Review Guardrails webhook configuration.",
                    "recommendation", "No action needed.",
                    "generatedAt", System.currentTimeMillis());
            return new AlertSnapshot(System.currentTimeMillis(), runtime, List.of(alert));
        }
    }
}
