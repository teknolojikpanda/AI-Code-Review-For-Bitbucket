package com.teknolojikpanda.bitbucket.aireviewer.service;

import com.teknolojikpanda.bitbucket.aireviewer.service.ReviewConcurrencyController.QueueStats;
import com.teknolojikpanda.bitbucket.aireviewer.service.ReviewConcurrencyController.QueueStats.ScopeQueueStats;
import com.teknolojikpanda.bitbucket.aireviewer.service.ReviewRateLimiter.RateLimitSnapshot;
import com.teknolojikpanda.bitbucket.aireviewer.service.ReviewRateLimiter.RateLimitSnapshot.BucketState;
import com.teknolojikpanda.bitbucket.aireviewer.service.ReviewQueueAuditService;
import com.teknolojikpanda.bitbucket.aireviewer.service.GuardrailsRateLimitOverrideService;

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

/**
 * Aggregates runtime telemetry for queue, worker, rate limiter, and retention guardrails.
 */
@Named
@Singleton
public class GuardrailsTelemetryService {

    private static final int DURATION_SAMPLE_LIMIT = 200;
    private static final int MODEL_STATS_SAMPLE_LIMIT = 600;

    private final ReviewConcurrencyController concurrencyController;
    private final ReviewWorkerPool workerPool;
    private final ReviewRateLimiter rateLimiter;
    private final ReviewHistoryService historyService;
    private final ReviewHistoryCleanupStatusService cleanupStatusService;
    private final ReviewSchedulerStateService schedulerStateService;
    private final ReviewHistoryCleanupAuditService cleanupAuditService;
    private final GuardrailsAlertDeliveryService deliveryService;
    private final GuardrailsWorkerNodeService workerNodeService;
    private final GuardrailsScalingAdvisor scalingAdvisor;
    private final ModelHealthService modelHealthService;
    private final ReviewQueueAuditService queueAuditService;
    private final GuardrailsRateLimitOverrideService overrideService;

    @Inject
    public GuardrailsTelemetryService(ReviewConcurrencyController concurrencyController,
                                      ReviewWorkerPool workerPool,
                                      ReviewRateLimiter rateLimiter,
                                      ReviewHistoryService historyService,
                                      ReviewHistoryCleanupStatusService cleanupStatusService,
                                      ReviewHistoryCleanupAuditService cleanupAuditService,
                                      ReviewSchedulerStateService schedulerStateService,
                                      GuardrailsAlertDeliveryService deliveryService,
                                      GuardrailsWorkerNodeService workerNodeService,
                                      GuardrailsScalingAdvisor scalingAdvisor,
                                      ModelHealthService modelHealthService,
                                      ReviewQueueAuditService queueAuditService,
                                      GuardrailsRateLimitOverrideService overrideService) {
        this.concurrencyController = Objects.requireNonNull(concurrencyController, "concurrencyController");
        this.workerPool = Objects.requireNonNull(workerPool, "workerPool");
        this.rateLimiter = Objects.requireNonNull(rateLimiter, "rateLimiter");
        this.historyService = Objects.requireNonNull(historyService, "historyService");
        this.cleanupStatusService = Objects.requireNonNull(cleanupStatusService, "cleanupStatusService");
        this.cleanupAuditService = Objects.requireNonNull(cleanupAuditService, "cleanupAuditService");
        this.schedulerStateService = Objects.requireNonNull(schedulerStateService, "schedulerStateService");
        this.deliveryService = Objects.requireNonNull(deliveryService, "deliveryService");
        this.workerNodeService = Objects.requireNonNull(workerNodeService, "workerNodeService");
        this.scalingAdvisor = Objects.requireNonNull(scalingAdvisor, "scalingAdvisor");
        this.modelHealthService = Objects.requireNonNull(modelHealthService, "modelHealthService");
        this.queueAuditService = Objects.requireNonNull(queueAuditService, "queueAuditService");
        this.overrideService = Objects.requireNonNull(overrideService, "overrideService");
    }

    /**
     * Builds a snapshot that mirrors what the Health UI needs for quick diagnosis.
     */
    public Map<String, Object> collectRuntimeSnapshot() {
        Map<String, Object> payload = new LinkedHashMap<>();
        QueueStats queueStats = concurrencyController.snapshot();
        payload.put("queue", queueSnapshotToMap(queueStats));
        payload.put("queueActions", queueActionsToList(queueAuditService.listRecentActions(25)));
        ReviewSchedulerStateService.SchedulerState schedulerState =
                queueStats != null ? queueStats.getSchedulerState() : schedulerStateService.getState();
        payload.put("schedulerState", schedulerStateToMap(schedulerState));
        ReviewWorkerPool.WorkerPoolSnapshot workerSnapshot = workerPool.snapshot();
        workerNodeService.recordLocalSnapshot(workerSnapshot);
        List<GuardrailsWorkerNodeService.WorkerNodeRecord> workerNodes = workerNodeService.listSnapshots();
        payload.put("workerPool", workerPoolStatsToMap(workerSnapshot));
        payload.put("workerPoolNodes", workerNodeSnapshotsToList(workerNodes));
        payload.put("rateLimiter", rateLimitStatsToMap(rateLimiter.snapshot()));
        payload.put("rateLimitOverrides", overridesToList(overrideService.listOverrides(false)));
        payload.put("scalingHints", scalingHintsToList(scalingAdvisor.evaluate(queueStats, workerNodes)));
        payload.put("reviewDurations", durationStatsToMap(historyService.getRecentDurationStats(DURATION_SAMPLE_LIMIT)));
        payload.put("retention", retentionToMap(cleanupStatusService.getStatus()));
        payload.put("modelStats", historyService.getRecentModelStats(MODEL_STATS_SAMPLE_LIMIT).toMap());
        payload.put("modelHealth", modelHealthService.snapshot());
        payload.put("generatedAt", System.currentTimeMillis());
        return payload;
    }

    /**
     * Exports flattened metric points alongside the richer runtime snapshot for external monitoring.
     */
    public Map<String, Object> exportMetrics() {
        Map<String, Object> runtime = collectRuntimeSnapshot();
        Map<String, Object> export = new LinkedHashMap<>();
        export.put("generatedAt", runtime.get("generatedAt"));
        export.put("runtime", runtime);
        export.put("metrics", collectMetricPoints(runtime));
        return export;
    }

    private List<Map<String, Object>> collectMetricPoints(Map<String, Object> runtime) {
        List<Map<String, Object>> metrics = new ArrayList<>();
        Map<String, Object> queue = asMap(runtime.get("queue"));
        Map<String, Object> worker = asMap(runtime.get("workerPool"));
        Map<String, Object> limiter = asMap(runtime.get("rateLimiter"));
        Map<String, Object> retention = asMap(runtime.get("retention"));
        Map<String, Object> schedule = asMap(retention.get("schedule"));
        Map<String, Object> schedulerState = asMap(runtime.get("schedulerState"));
        Map<String, Object> durations = asMap(runtime.get("reviewDurations"));
        GuardrailsAlertDeliveryService.Aggregates deliveryAgg = deliveryService.aggregateRecentDeliveries(200);

        Number maxConcurrent = toNumber(queue.get("maxConcurrent"));
        Number active = toNumber(queue.get("active"));
        if (maxConcurrent != null && active != null) {
            addMetric(metrics, "ai.queue.availableSlots",
                    Math.max(0, maxConcurrent.intValue() - active.intValue()),
                    "count", "Available concurrent review slots");
        }
        addMetric(metrics, "ai.queue.active", queue.get("active"), "count", "Currently running AI reviews");
        addMetric(metrics, "ai.queue.waiting", queue.get("waiting"), "count", "Queued AI reviews awaiting a slot");
        addMetric(metrics, "ai.queue.maxConcurrent", queue.get("maxConcurrent"), "count", "Configured concurrent limit");
        addMetric(metrics, "ai.queue.maxQueued", queue.get("maxQueued"), "count", "Total queued capacity");
        addMetric(metrics, "ai.queue.repoScopeLimit", queue.get("maxQueuedPerRepo"), "count", "Max queued per repository");
        addMetric(metrics, "ai.queue.projectScopeLimit", queue.get("maxQueuedPerProject"), "count", "Max queued per project");
        addMetric(metrics, "ai.queue.repoPressureScopes", sizeOfList(queue.get("repoWaiters")), "count",
                "Repositories currently hitting per-scope queue caps");
        addMetric(metrics, "ai.queue.projectPressureScopes", sizeOfList(queue.get("projectWaiters")), "count",
                "Projects currently hitting per-scope queue caps");

        String mode = schedulerState.containsKey("mode") ? Objects.toString(schedulerState.get("mode"), "ACTIVE") : "ACTIVE";
        addMetric(metrics, "ai.queue.scheduler.paused", "PAUSED".equalsIgnoreCase(mode) ? 1 : 0,
                "flag", "1 indicates the scheduler is paused");

        addMetric(metrics, "ai.worker.activeThreads", worker.get("activeThreads"), "count", "Active worker threads");
        addMetric(metrics, "ai.worker.configuredSize", worker.get("configuredSize"), "count", "Configured worker pool size");
        addMetric(metrics, "ai.worker.queuedTasks", worker.get("queuedTasks"), "count", "Pending worker tasks");

        addMetric(metrics, "ai.rateLimiter.repoLimitPerHour", limiter.get("repoLimitPerHour"), "reviews/hour",
                "Per-repository review budget");
        addMetric(metrics, "ai.rateLimiter.projectLimitPerHour", limiter.get("projectLimitPerHour"), "reviews/hour",
                "Per-project review budget");
        addMetric(metrics, "ai.rateLimiter.trackedRepoBuckets", limiter.get("trackedRepoBuckets"), "count",
                "Repositories tracked by the limiter");
        addMetric(metrics, "ai.rateLimiter.trackedProjectBuckets", limiter.get("trackedProjectBuckets"), "count",
                "Projects tracked by the limiter");
        addLimiterBucketMetrics(metrics, "repo", asList(limiter.get("topRepoBuckets")), 5);
        addLimiterBucketMetrics(metrics, "project", asList(limiter.get("topProjectBuckets")), 5);

        addMetric(metrics, "ai.retention.windowDays", retention.get("retentionDays"), "days",
                "Retention window applied to AI review history");
        addMetric(metrics, "ai.retention.totalEntries", retention.get("totalEntries"), "items",
                "Total AI review history rows");
        addMetric(metrics, "ai.retention.entriesOlderThanWindow", retention.get("entriesOlderThanRetention"), "items",
                "History rows older than the retention window");

        addMetric(metrics, "ai.retention.cleanup.enabled", schedule.get("enabled"), "flag",
                "1 indicates automatic cleanup is enabled");
        addMetric(metrics, "ai.retention.cleanup.intervalMinutes", schedule.get("intervalMinutes"), "minutes",
                "Scheduled cleanup cadence");
        addMetric(metrics, "ai.retention.cleanup.batchSize", schedule.get("batchSize"), "items",
                "Maximum rows purged per cleanup run");
        addMetric(metrics, "ai.retention.cleanup.lastDurationMs", schedule.get("lastDurationMs"), "ms",
                "Duration of the last cleanup run");
        addMetric(metrics, "ai.retention.cleanup.lastDeletedHistories", schedule.get("lastDeletedHistories"), "items",
                "History rows deleted in the last cleanup run");
        addMetric(metrics, "ai.retention.cleanup.lastDeletedChunks", schedule.get("lastDeletedChunks"), "items",
                "Chunk rows deleted in the last cleanup run");

        Long lastRun = toLong(schedule.get("lastRun"));
        if (lastRun != null && lastRun > 0) {
            long ageSeconds = Math.max(0, (System.currentTimeMillis() - lastRun) / 1000);
            addMetric(metrics, "ai.retention.cleanup.lastRunAgeSeconds", ageSeconds, "seconds",
                    "Seconds since the last cleanup completed");
        }
        if (schedule.containsKey("lastError") && schedule.get("lastError") != null) {
            addMetric(metrics, "ai.retention.cleanup.lastErrorFlag", 1, "flag",
                    "1 indicates the last cleanup attempt failed");
        } else {
            addMetric(metrics, "ai.retention.cleanup.lastErrorFlag", 0, "flag",
                    "1 indicates the last cleanup attempt failed");
        }

        addMetric(metrics, "ai.review.duration.samples", durations.get("samples"), "samples",
                "Recent review duration samples captured for ETA calculations");
        addMetric(metrics, "ai.alerts.deliveries.samples", deliveryAgg.getSamples(), "deliveries",
                "Deliveries sampled for guardrails webhook monitoring");
        addMetric(metrics, "ai.alerts.deliveries.failures", deliveryAgg.getFailures(), "deliveries",
                "Failed deliveries in recent sample");
        addMetric(metrics, "ai.alerts.deliveries.failureRate", deliveryAgg.getFailureRate(), "ratio",
                "Fraction of failed deliveries in recent sample");

        return metrics;
    }

    private Map<String, Object> queueSnapshotToMap(QueueStats stats) {
        if (stats == null) {
            return Collections.emptyMap();
        }
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("maxConcurrent", stats.getMaxConcurrent());
        map.put("maxQueued", stats.getMaxQueued());
        map.put("active", stats.getActive());
        map.put("waiting", stats.getWaiting());
        map.put("capturedAt", stats.getCapturedAt());
        map.put("maxQueuedPerRepo", stats.getMaxQueuedPerRepo());
        map.put("maxQueuedPerProject", stats.getMaxQueuedPerProject());
        map.put("repoWaiters", scopeStatsToList(stats.getTopRepoWaiters()));
        map.put("projectWaiters", scopeStatsToList(stats.getTopProjectWaiters()));
        map.put("activeRuns", activeRunsToList(stats.getActiveRuns()));
        return map;
    }

    private Map<String, Object> workerPoolStatsToMap(ReviewWorkerPool.WorkerPoolSnapshot snapshot) {
        if (snapshot == null) {
            return Collections.emptyMap();
        }
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("configuredSize", snapshot.getConfiguredSize());
        map.put("activeThreads", snapshot.getActiveThreads());
        map.put("queuedTasks", snapshot.getQueuedTasks());
        map.put("currentPoolSize", snapshot.getCurrentPoolSize());
        map.put("largestPoolSize", snapshot.getLargestPoolSize());
        map.put("totalTasks", snapshot.getTotalTasks());
        map.put("completedTasks", snapshot.getCompletedTasks());
        map.put("capturedAt", snapshot.getCapturedAt());
        return map;
    }

    private List<Map<String, Object>> workerNodeSnapshotsToList(List<GuardrailsWorkerNodeService.WorkerNodeRecord> records) {
        if (records == null || records.isEmpty()) {
            return Collections.emptyList();
        }
        List<Map<String, Object>> list = new ArrayList<>(records.size());
        for (GuardrailsWorkerNodeService.WorkerNodeRecord record : records) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("nodeId", record.getNodeId());
            map.put("nodeName", record.getNodeName());
            map.put("configuredSize", record.getConfiguredSize());
            map.put("activeThreads", record.getActiveThreads());
            map.put("queuedTasks", record.getQueuedTasks());
            map.put("currentPoolSize", record.getCurrentPoolSize());
            map.put("largestPoolSize", record.getLargestPoolSize());
            map.put("totalTasks", record.getTotalTasks());
            map.put("completedTasks", record.getCompletedTasks());
            map.put("capturedAt", record.getCapturedAt());
            map.put("utilization", record.getUtilization());
            map.put("stale", record.isStale());
            list.add(map);
        }
        return list;
    }

    private List<Map<String, Object>> scalingHintsToList(List<GuardrailsScalingAdvisor.ScalingHint> hints) {
        if (hints == null || hints.isEmpty()) {
            return Collections.emptyList();
        }
        List<Map<String, Object>> list = new ArrayList<>(hints.size());
        for (GuardrailsScalingAdvisor.ScalingHint hint : hints) {
            list.add(hint.toMap());
        }
        return list;
    }

    private List<Map<String, Object>> queueActionsToList(List<ReviewConcurrencyController.QueueStats.QueueAction> actions) {
        if (actions == null || actions.isEmpty()) {
            return Collections.emptyList();
        }
        List<Map<String, Object>> list = new ArrayList<>(actions.size());
        for (ReviewConcurrencyController.QueueStats.QueueAction action : actions) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("timestamp", action.getTimestamp());
            map.put("action", action.getAction());
            map.put("actor", action.getActor());
            map.put("runId", action.getRunId());
            map.put("projectKey", action.getProjectKey());
            map.put("repositorySlug", action.getRepositorySlug());
            map.put("pullRequestId", action.getPullRequestId());
            map.put("manual", action.isManual());
            map.put("update", action.isUpdate());
            map.put("force", action.isForce());
            map.put("requestedBy", action.getRequestedBy());
            map.put("note", action.getNote());
            list.add(map);
        }
        return list;
    }

    private List<Map<String, Object>> overridesToList(List<GuardrailsRateLimitOverrideService.OverrideRecord> overrides) {
        if (overrides == null || overrides.isEmpty()) {
            return Collections.emptyList();
        }
        List<Map<String, Object>> list = new ArrayList<>(overrides.size());
        for (GuardrailsRateLimitOverrideService.OverrideRecord override : overrides) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("id", override.getId());
            map.put("scope", override.getScope().name());
            map.put("identifier", override.getIdentifier());
            map.put("limitPerHour", override.getLimitPerHour());
            map.put("expiresAt", override.getExpiresAt());
            map.put("createdAt", override.getCreatedAt());
            map.put("createdBy", override.getCreatedBy());
            map.put("createdByDisplayName", override.getCreatedByDisplayName());
            map.put("reason", override.getReason());
            list.add(map);
        }
        return list;
    }

    private Map<String, Object> rateLimitStatsToMap(RateLimitSnapshot snapshot) {
        if (snapshot == null) {
            return Collections.emptyMap();
        }
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("repoLimitPerHour", snapshot.getRepoLimit());
        map.put("projectLimitPerHour", snapshot.getProjectLimit());
        map.put("trackedRepoBuckets", snapshot.getTrackedRepoBuckets());
        map.put("trackedProjectBuckets", snapshot.getTrackedProjectBuckets());
        map.put("capturedAt", snapshot.getCapturedAt());
        map.put("topRepoBuckets", bucketStatesToList(snapshot.getTopRepoBuckets()));
        map.put("topProjectBuckets", bucketStatesToList(snapshot.getTopProjectBuckets()));
        return map;
    }

    private Map<String, Object> retentionToMap(ReviewHistoryCleanupStatusService.Status status) {
        Map<String, Object> map = new LinkedHashMap<>(historyService.getRetentionStats(status.getRetentionDays()));
        map.put("schedule", cleanupStatusToMap(status));
        map.put("recentRuns", cleanupAuditService.listRecent(10));
        return map;
    }

    private Map<String, Object> durationStatsToMap(ReviewHistoryService.DurationStats stats) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("samples", stats.getSamples());
        Map<String, Object> averages = new LinkedHashMap<>();
        averages.put("globalMs", stats.estimate(null, null, 0));
        map.put("averages", averages);
        return map;
    }

    private Map<String, Object> cleanupStatusToMap(ReviewHistoryCleanupStatusService.Status status) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("enabled", status.isEnabled());
        map.put("retentionDays", status.getRetentionDays());
        map.put("batchSize", status.getBatchSize());
        map.put("intervalMinutes", status.getIntervalMinutes());
        map.put("lastRun", status.getLastRun());
        map.put("lastDurationMs", status.getLastDurationMs());
        map.put("lastDeletedHistories", status.getLastDeletedHistories());
        map.put("lastDeletedChunks", status.getLastDeletedChunks());
        map.put("lastError", status.getLastError());
        return map;
    }

    private Map<String, Object> schedulerStateToMap(ReviewSchedulerStateService.SchedulerState state) {
        if (state == null) {
            return Collections.emptyMap();
        }
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("mode", state.getMode().name());
        map.put("reason", state.getReason());
        map.put("updatedBy", state.getUpdatedBy());
        map.put("updatedByDisplayName", state.getUpdatedByDisplayName());
        map.put("updatedAt", state.getUpdatedAt());
        return map;
    }

    private List<Map<String, Object>> scopeStatsToList(List<ScopeQueueStats> stats) {
        if (stats == null || stats.isEmpty()) {
            return Collections.emptyList();
        }
        List<Map<String, Object>> list = new ArrayList<>();
        for (ScopeQueueStats scope : stats) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("scope", scope.getScope());
            map.put("waiting", scope.getWaiting());
            map.put("limit", scope.getLimit());
            list.add(map);
        }
        return list;
    }

    private List<Map<String, Object>> activeRunsToList(List<QueueStats.ActiveRunEntry> runs) {
        if (runs == null || runs.isEmpty()) {
            return Collections.emptyList();
        }
        List<Map<String, Object>> list = new ArrayList<>(runs.size());
        long now = System.currentTimeMillis();
        for (QueueStats.ActiveRunEntry entry : runs) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("runId", entry.getRunId());
            map.put("projectKey", entry.getProjectKey());
            map.put("repositorySlug", entry.getRepositorySlug());
            map.put("pullRequestId", entry.getPullRequestId());
            map.put("manual", entry.isManual());
            map.put("update", entry.isUpdate());
            map.put("force", entry.isForce());
            map.put("startedAt", entry.getStartedAt());
            map.put("runningMs", Math.max(0, now - entry.getStartedAt()));
            map.put("cancelRequested", entry.isCancelRequested());
            map.put("requestedBy", entry.getRequestedBy());
            list.add(map);
        }
        return list;
    }

    private List<Map<String, Object>> bucketStatesToList(Map<String, BucketState> states) {
        if (states == null || states.isEmpty()) {
            return Collections.emptyList();
        }
        List<Map<String, Object>> list = new ArrayList<>();
        states.forEach((scope, state) -> {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("scope", scope);
            map.put("consumed", state.getConsumed());
            map.put("limit", state.getLimit());
            map.put("remaining", state.getRemaining());
            map.put("resetInMs", state.getResetInMs());
             map.put("updatedAt", state.getUpdatedAt());
             map.put("throttledCount", state.getThrottledCount());
             map.put("lastThrottleAt", state.getLastThrottleAt());
             map.put("averageRetryAfterMs", state.getAverageRetryAfterMs());
            list.add(map);
        });
        return list;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object value) {
        if (value instanceof Map) {
            return (Map<String, Object>) value;
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

    private void addMetric(List<Map<String, Object>> metrics,
                           String name,
                           Object rawValue,
                           String unit,
                           String description) {
        Number number = toNumber(rawValue);
        if (number == null) {
            return;
        }
        Map<String, Object> metric = new LinkedHashMap<>();
        metric.put("name", name);
        metric.put("value", number);
        if (unit != null) {
            metric.put("unit", unit);
        }
        if (description != null) {
            metric.put("description", description);
        }
        metrics.add(metric);
    }

    private void addLimiterBucketMetrics(List<Map<String, Object>> metrics,
                                         String scopeType,
                                         List<Map<String, Object>> buckets,
                                         int maxBuckets) {
        if (buckets == null || buckets.isEmpty()) {
            return;
        }
        int count = 0;
        for (Map<String, Object> bucket : buckets) {
            if (count++ >= maxBuckets) {
                break;
            }
            String scope = sanitizeMetricScope((String) bucket.get("scope"));
            String prefix = "ai.rateLimiter." + scopeType + "." + scope;
            addMetric(metrics, prefix + ".consumed", bucket.get("consumed"), "reviews",
                    "Reviews consumed for " + scopeType + " " + scope);
            addMetric(metrics, prefix + ".remaining", bucket.get("remaining"), "reviews",
                    "Reviews remaining before throttling for " + scopeType + " " + scope);
            addMetric(metrics, prefix + ".resetInMs", bucket.get("resetInMs"), "ms",
                    "Milliseconds until rate window resets for " + scopeType + " " + scope);
            addMetric(metrics, prefix + ".throttledCount", bucket.get("throttledCount"), "events",
                    "Throttle events recorded for " + scopeType + " " + scope + " within the last window");
        }
    }

    private String sanitizeMetricScope(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return "unknown";
        }
        return raw.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-");
    }

    private Number toNumber(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number) {
            return (Number) value;
        }
        if (value instanceof Boolean) {
            return (Boolean) value ? 1 : 0;
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
        if (number == null) {
            return null;
        }
        return number.longValue();
    }

    private int sizeOfList(Object value) {
        if (value instanceof List) {
            return ((List<?>) value).size();
        }
        return 0;
    }
}
