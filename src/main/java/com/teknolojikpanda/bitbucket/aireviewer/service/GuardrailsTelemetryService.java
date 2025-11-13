package com.teknolojikpanda.bitbucket.aireviewer.service;

import com.teknolojikpanda.bitbucket.aireviewer.service.ReviewConcurrencyController.QueueStats;
import com.teknolojikpanda.bitbucket.aireviewer.service.ReviewConcurrencyController.QueueStats.ScopeQueueStats;
import com.teknolojikpanda.bitbucket.aireviewer.service.ReviewRateLimiter.RateLimitSnapshot;
import com.teknolojikpanda.bitbucket.aireviewer.service.ReviewRateLimiter.RateLimitSnapshot.BucketState;

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
 * Aggregates runtime telemetry for queue, worker, rate limiter, and retention guardrails.
 */
@Named
@Singleton
public class GuardrailsTelemetryService {

    private static final int DURATION_SAMPLE_LIMIT = 200;
    private static final int MODEL_STATS_SAMPLE_LIMIT = 600;
    private static final int CIRCUIT_SAMPLE_LIMIT = 400;

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
    private final GuardrailsRateLimitStore rateLimitStore;
    private final GuardrailsRolloutService rolloutService;

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
                                      GuardrailsRateLimitOverrideService overrideService,
                                      GuardrailsRateLimitStore rateLimitStore,
                                      GuardrailsRolloutService rolloutService) {
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
        this.rateLimitStore = Objects.requireNonNull(rateLimitStore, "rateLimitStore");
        this.rolloutService = Objects.requireNonNull(rolloutService, "rolloutService");
    }

    /**
     * Builds a snapshot that mirrors what the Health UI needs for quick diagnosis.
     */
    public Map<String, Object> collectRuntimeSnapshot() {
        Map<String, Object> payload = new LinkedHashMap<>();
        QueueStats queueStats = concurrencyController.snapshot();
        payload.put("queue", queueSnapshotToMap(queueStats));
        List<Map<String, Object>> queueActions = queueActionsToList(queueAuditService.listRecentActions(25));
        payload.put("queueActions", queueActions);
        ReviewSchedulerStateService.SchedulerState schedulerState =
                queueStats != null ? queueStats.getSchedulerState() : schedulerStateService.getState();
        payload.put("schedulerState", schedulerStateToMap(schedulerState));
        ReviewWorkerPool.WorkerPoolSnapshot workerSnapshot = workerPool.snapshot();
        workerNodeService.recordLocalSnapshot(workerSnapshot);
        List<GuardrailsWorkerNodeService.WorkerNodeRecord> workerNodes = workerNodeService.listSnapshots();
        List<Map<String, Object>> workerNodeTimeline = workerNodeSnapshotsToList(workerNodes);
        payload.put("workerPool", workerPoolStatsToMap(workerSnapshot));
        payload.put("workerPoolNodes", workerNodeTimeline);
        payload.put("rateLimiter", rateLimitStatsToMap(rateLimiter.snapshot()));
        payload.put("rateLimitOverrides", overridesToList(overrideService.listOverrides(false)));
        payload.put("rollout", rolloutService.describeTelemetry());
        payload.put("scalingHints", scalingHintsToList(scalingAdvisor.evaluate(queueStats, workerNodes)));
        payload.put("reviewDurations", durationStatsToMap(historyService.getRecentDurationStats(DURATION_SAMPLE_LIMIT)));
        payload.put("retention", retentionToMap(cleanupStatusService.getStatus()));
        ReviewHistoryService.ModelStats modelStats = historyService.getRecentModelStats(MODEL_STATS_SAMPLE_LIMIT);
        payload.put("modelStats", modelStats != null ? modelStats.toMap() : Collections.emptyMap());
        payload.put("modelHealth", modelHealthService.snapshot());
        ReviewHistoryService.CircuitStats circuitStats = historyService.getRecentCircuitStats(CIRCUIT_SAMPLE_LIMIT);
        payload.put("circuitBreaker", circuitStats != null ? circuitStats.toMap() : Collections.emptyMap());
        payload.put("healthTimeline", buildHealthTimeline(queueActions, workerNodeTimeline));
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
        export.put("alertThresholds", buildAlertThresholds(runtime));
        return export;
    }

    private List<Map<String, Object>> collectMetricPoints(Map<String, Object> runtime) {
        List<Map<String, Object>> metrics = new ArrayList<>();
        long now = System.currentTimeMillis();
        Map<String, Object> queue = asMap(runtime.get("queue"));
        Map<String, Object> worker = asMap(runtime.get("workerPool"));
        Map<String, Object> limiter = asMap(runtime.get("rateLimiter"));
        Map<String, Object> retention = asMap(runtime.get("retention"));
        Map<String, Object> schedule = asMap(retention.get("schedule"));
        Map<String, Object> schedulerState = asMap(runtime.get("schedulerState"));
        Map<String, Object> durations = asMap(runtime.get("reviewDurations"));
        Map<String, Object> circuit = asMap(runtime.get("circuitBreaker"));
        Map<String, Object> modelStats = asMap(runtime.get("modelStats"));
        GuardrailsAlertDeliveryService.Aggregates deliveryAgg = deliveryService.aggregateRecentDeliveries(200);
        GuardrailsAlertDeliveryService.AcknowledgementStats ackStats = deliveryService.computeAcknowledgementStats(200);

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
        List<Map<String, Object>> repoBuckets = asList(limiter.get("topRepoBuckets"));
        List<Map<String, Object>> projectBuckets = asList(limiter.get("topProjectBuckets"));
        addLimiterBucketMetrics(metrics, "repo", repoBuckets, 5);
        addLimiterBucketMetrics(metrics, "project", projectBuckets, 5);

        long repoThrottleEvents = sumListField(repoBuckets, "throttledCount");
        long projectThrottleEvents = sumListField(projectBuckets, "throttledCount");
        addMetric(metrics, "ai.rateLimiter.repoThrottles", repoThrottleEvents, "events",
                "Throttle incidents recorded across sampled repositories");
        addMetric(metrics, "ai.rateLimiter.projectThrottles", projectThrottleEvents, "events",
                "Throttle incidents recorded across sampled projects");
        addMetric(metrics, "ai.rateLimiter.totalThrottles", repoThrottleEvents + projectThrottleEvents, "events",
                "Total throttle incidents seen across sampled scopes");
        double repoAvgRetry = averageListField(repoBuckets, "averageRetryAfterMs");
        double projectAvgRetry = averageListField(projectBuckets, "averageRetryAfterMs");
        addMetric(metrics, "ai.rateLimiter.repo.avgRetryAfterMs", repoAvgRetry, "ms",
                "Average retry-after window observed for throttled repositories");
        addMetric(metrics, "ai.rateLimiter.project.avgRetryAfterMs", projectAvgRetry, "ms",
                "Average retry-after window observed for throttled projects");
        long repoMaxRetry = maxListField(repoBuckets, "averageRetryAfterMs");
        long projectMaxRetry = maxListField(projectBuckets, "averageRetryAfterMs");
        addMetric(metrics, "ai.rateLimiter.repo.maxRetryAfterMs", repoMaxRetry, "ms",
                "Longest retry-after window observed for throttled repositories");
        addMetric(metrics, "ai.rateLimiter.project.maxRetryAfterMs", projectMaxRetry, "ms",
                "Longest retry-after window observed for throttled projects");
        long repoThrottleAge = ageSinceMostRecent(repoBuckets, "lastThrottleAt", now);
        long projectThrottleAge = ageSinceMostRecent(projectBuckets, "lastThrottleAt", now);
        if (repoThrottleAge >= 0) {
            addMetric(metrics, "ai.rateLimiter.repo.lastThrottleAgeSeconds", repoThrottleAge, "seconds",
                    "Seconds since the most recent repository throttle event");
        }
        if (projectThrottleAge >= 0) {
            addMetric(metrics, "ai.rateLimiter.project.lastThrottleAgeSeconds", projectThrottleAge, "seconds",
                    "Seconds since the most recent project throttle event");
        }

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
        addMetric(metrics, "ai.retention.cleanup.windowStartHour", schedule.get("windowStartHour"), "hour",
                "Hour (0-23) when the cleanup window opens");
        addMetric(metrics, "ai.retention.cleanup.windowDurationMinutes", schedule.get("windowDurationMinutes"), "minutes",
                "Duration of the daily cleanup window");
        addMetric(metrics, "ai.retention.cleanup.maxBatchesPerWindow", schedule.get("maxBatchesPerWindow"), "batches",
                "Maximum delete batches executed per window");
        addMetric(metrics, "ai.retention.cleanup.lastDurationMs", schedule.get("lastDurationMs"), "ms",
                "Duration of the last cleanup run");
        addMetric(metrics, "ai.retention.cleanup.lastDeletedHistories", schedule.get("lastDeletedHistories"), "items",
                "History rows deleted in the last cleanup run");
        addMetric(metrics, "ai.retention.cleanup.lastDeletedChunks", schedule.get("lastDeletedChunks"), "items",
                "Chunk rows deleted in the last cleanup run");
        addMetric(metrics, "ai.retention.cleanup.lastBatchesExecuted", schedule.get("lastBatchesExecuted"), "batches",
                "Delete batches executed in the most recent cleanup window");

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
        addMetric(metrics, "ai.alerts.pendingAcknowledgements", ackStats.getPendingCount(), "deliveries",
                "Alert deliveries awaiting acknowledgement");
        long oldestPendingSeconds = ackStats.getOldestPendingMillis() > 0
                ? ackStats.getOldestPendingMillis() / 1000L
                : 0L;
        addMetric(metrics, "ai.alerts.pendingOldestSeconds", oldestPendingSeconds, "seconds",
                "Age of the oldest unacknowledged alert delivery");
        double avgAckSeconds = ackStats.getAverageAckMillis() / 1000d;
        addMetric(metrics, "ai.alerts.ack.latencySecondsAvg", avgAckSeconds, "seconds",
                "Average acknowledgement latency across recent alerts");

        List<Map<String, Object>> modelEntries = asList(modelStats.get("entries"));
        long totalInvocations = sumListField(modelEntries, "totalInvocations");
        long failureCount = sumListField(modelEntries, "failureCount");
        long timeoutCount = sumListField(modelEntries, "timeoutCount");
        long totalErrors = failureCount + timeoutCount;
        addMetric(metrics, "ai.model.invocations", totalInvocations, "chunks",
                "Model invocations sampled for recent telemetry");
        addMetric(metrics, "ai.model.failures", failureCount, "chunks",
                "Model calls that failed before completion");
        addMetric(metrics, "ai.model.timeouts", timeoutCount, "chunks",
                "Model calls that timed out");
        addMetric(metrics, "ai.model.errors", totalErrors, "chunks",
                "Total failed or timed-out model calls");
        addMetric(metrics, "ai.model.sampledEntries", modelStats.get("scannedSamples"), "chunks",
                "Chunk samples scanned when computing model stats");
        if (totalInvocations > 0 && totalErrors >= 0) {
            double errorRate = (double) totalErrors / (double) totalInvocations;
            addMetric(metrics, "ai.model.errorRate", errorRate, "ratio",
                    "Error rate derived from sampled model invocations");
        }

        addMetric(metrics, "ai.breaker.samples", circuit.get("samples"), "samples",
                "Circuit breaker snapshots aggregated from recent history");
        addMetric(metrics, "ai.breaker.openEvents", circuit.get("openEvents"), "events",
                "Number of times the breaker transitioned to OPEN");
        addMetric(metrics, "ai.breaker.blockedCalls", circuit.get("blockedCalls"), "calls",
                "Calls blocked while the breaker was OPEN");
        addMetric(metrics, "ai.breaker.blockedCallsAvgPerSample", circuit.get("avgBlockedCallsPerSample"), "calls/sample",
                "Average blocked calls per sampled snapshot");
        addMetric(metrics, "ai.breaker.failureCount", circuit.get("failureCount"), "events",
                "Recorded failures contributing to breaker state");
        addMetric(metrics, "ai.breaker.clientBlockedCalls", circuit.getOrDefault("clientBlockedCalls", 0), "calls",
                "Client-side blocked calls observed by the reviewer");
        addMetric(metrics, "ai.breaker.clientHardFailures", circuit.getOrDefault("clientHardFailures", 0), "calls",
                "Client-side hard failures (non-recoverable) reported by the reviewer");
        Number openRatio = toNumber(circuit.get("openSampleRatio"));
        if (openRatio != null) {
            addMetric(metrics, "ai.breaker.openSampleRatio", openRatio, "ratio",
                    "Fraction of recent samples captured while the breaker was OPEN");
        }
        Map<String, Object> breakerStates = asMap(circuit.get("stateCounts"));
        if (!breakerStates.isEmpty()) {
            addMetric(metrics, "ai.breaker.state.openSamples", breakerStates.get("OPEN"), "samples",
                    "Samples captured while circuit was OPEN");
            addMetric(metrics, "ai.breaker.state.halfOpenSamples", breakerStates.get("HALF_OPEN"), "samples",
                    "Samples captured while circuit was HALF_OPEN");
            addMetric(metrics, "ai.breaker.state.closedSamples", breakerStates.get("CLOSED"), "samples",
                    "Samples captured while circuit was CLOSED");
        }

        return metrics;
    }

    private Map<String, Object> buildHealthTimeline(List<Map<String, Object>> queueActions,
                                                    List<Map<String, Object>> workerSnapshots) {
        Map<String, Object> timeline = new LinkedHashMap<>();
        timeline.put("queueActions", queueActions != null ? queueActions : Collections.emptyList());
        timeline.put("workerSnapshots", workerSnapshots != null ? workerSnapshots : Collections.emptyList());
        timeline.put("rateLimitIncidents", throttleIncidentsToList(rateLimitStore.fetchRecentIncidents(15)));
        timeline.put("alertDeliveries", alertDeliveriesToList(deliveryService.listDeliveries(0, 10).getValues()));
        return timeline;
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

    private List<Map<String, Object>> throttleIncidentsToList(List<GuardrailsRateLimitStore.ThrottleIncident> incidents) {
        if (incidents == null || incidents.isEmpty()) {
            return Collections.emptyList();
        }
        List<Map<String, Object>> list = new ArrayList<>(incidents.size());
        for (GuardrailsRateLimitStore.ThrottleIncident incident : incidents) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("scope", incident.getScope().name());
            map.put("identifier", incident.getIdentifier());
            map.put("projectKey", incident.getProjectKey());
            map.put("repositorySlug", incident.getRepositorySlug());
            map.put("occurredAt", incident.getOccurredAt());
            map.put("limitPerHour", incident.getLimitPerHour());
            map.put("retryAfterMs", incident.getRetryAfterMs());
            map.put("reason", incident.getReason());
            list.add(map);
        }
        return list;
    }

    private List<Map<String, Object>> alertDeliveriesToList(List<GuardrailsAlertDeliveryService.Delivery> deliveries) {
        if (deliveries == null || deliveries.isEmpty()) {
            return Collections.emptyList();
        }
        List<Map<String, Object>> list = new ArrayList<>(deliveries.size());
        for (GuardrailsAlertDeliveryService.Delivery delivery : deliveries) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("id", delivery.getId());
            map.put("channelDescription", delivery.getChannelDescription());
            map.put("channelUrl", delivery.getChannelUrl());
            map.put("deliveredAt", delivery.getDeliveredAt());
            map.put("success", delivery.isSuccess());
            map.put("httpStatus", delivery.getHttpStatus());
            map.put("acknowledged", delivery.isAcknowledged());
            map.put("ackTimestamp", delivery.getAckTimestamp());
            map.put("ackUserDisplayName", delivery.getAckUserDisplayName());
            map.put("test", delivery.isTest());
            list.add(map);
        }
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

    private long sumListField(List<Map<String, Object>> list, String field) {
        if (list == null || list.isEmpty()) {
            return 0L;
        }
        long sum = 0L;
        for (Map<String, Object> entry : list) {
            if (entry == null) {
                continue;
            }
            Number number = toNumber(entry.get(field));
            if (number != null) {
                sum += number.longValue();
            }
        }
        return sum;
    }

    private double averageListField(List<Map<String, Object>> list, String field) {
        if (list == null || list.isEmpty()) {
            return 0d;
        }
        double sum = 0d;
        int count = 0;
        for (Map<String, Object> entry : list) {
            if (entry == null) {
                continue;
            }
            Number number = toNumber(entry.get(field));
            if (number != null) {
                sum += number.doubleValue();
                count++;
            }
        }
        return count == 0 ? 0d : sum / count;
    }

    private long maxListField(List<Map<String, Object>> list, String field) {
        long max = 0L;
        if (list == null || list.isEmpty()) {
            return max;
        }
        for (Map<String, Object> entry : list) {
            if (entry == null) {
                continue;
            }
            Number number = toNumber(entry.get(field));
            if (number != null) {
                long value = number.longValue();
                if (value > max) {
                    max = value;
                }
            }
        }
        return max;
    }

    private long ageSinceMostRecent(List<Map<String, Object>> list, String field, long now) {
        if (list == null || list.isEmpty()) {
            return -1L;
        }
        long latest = 0L;
        for (Map<String, Object> entry : list) {
            if (entry == null) {
                continue;
            }
            Number number = toNumber(entry.get(field));
            if (number != null) {
                long value = number.longValue();
                if (value > latest) {
                    latest = value;
                }
            }
        }
        if (latest <= 0L) {
            return -1L;
        }
        return Math.max(0L, (now - latest) / 1000L);
    }

    private Map<String, Object> buildAlertThresholds(Map<String, Object> runtime) {
        Map<String, Object> thresholds = new LinkedHashMap<>();
        thresholds.put("ai.queue.availableSlots",
                threshold(1, 0, "Fire when all concurrent review slots are consumed.", "count", "lte"));
        thresholds.put("ai.queue.waiting",
                threshold(5, 15, "Warn when reviews pile up in the scheduler queue.", "count", "gte"));
        thresholds.put("ai.queue.scheduler.paused",
                threshold(1, 1, "Scheduler paused outside maintenance windows.", "flag", "eq"));
        thresholds.put("ai.worker.queuedTasks",
                threshold(10, 25, "Worker backlog suggests thread starvation.", "tasks", "gte"));
        thresholds.put("ai.rateLimiter.totalThrottles",
                threshold(5, 20, "High throttle volume indicates limits too low or burst credits needed.", "events", "gte"));
        thresholds.put("ai.alerts.deliveries.failureRate",
                threshold(0.1, 0.25, "Alert webhooks failing above healthy error budget.", "ratio", "gte"));
        thresholds.put("ai.retention.cleanup.lastRunAgeSeconds",
                threshold(TimeUnit.HOURS.toSeconds(24), TimeUnit.HOURS.toSeconds(48),
                        "Cleanup job overdue; history backlog may grow.", "seconds", "gte"));
        thresholds.put("ai.retention.cleanup.lastErrorFlag",
                threshold(1, 1, "Last cleanup job failed—manual intervention required.", "flag", "eq"));
        thresholds.put("ai.model.errorRate",
                threshold(0.05, 0.1, "Model failures/timeouts impacting review quality.", "ratio", "gte"));
        thresholds.put("ai.breaker.openSampleRatio",
                threshold(0.1, 0.25, "Circuit breaker stuck open; AI vendor likely degraded.", "ratio", "gte"));
        thresholds.put("ai.breaker.blockedCallsAvgPerSample",
                threshold(1, 5, "Breaker blocking multiple calls per sample.", "calls/sample", "gte"));
        thresholds.put("ai.rateLimiter.repo.avgRetryAfterMs",
                threshold(TimeUnit.SECONDS.toMillis(15), TimeUnit.SECONDS.toMillis(30),
                        "Repositories seeing long retry-after windows.", "ms", "gte"));
        thresholds.put("ai.rateLimiter.project.avgRetryAfterMs",
                threshold(TimeUnit.SECONDS.toMillis(20), TimeUnit.SECONDS.toMillis(45),
                        "Projects seeing long retry-after windows.", "ms", "gte"));
        thresholds.put("ai.alerts.pendingAcknowledgements",
                threshold(1, 5, "Outstanding alerts require acknowledgement.", "deliveries", "gte"));
        thresholds.put("ai.alerts.pendingOldestSeconds",
                threshold(TimeUnit.MINUTES.toSeconds(15), TimeUnit.MINUTES.toSeconds(60),
                        "Oldest unacknowledged alert is stale.", "seconds", "gte"));
        thresholds.put("ai.alerts.ack.latencySecondsAvg",
                threshold(TimeUnit.MINUTES.toSeconds(5), TimeUnit.MINUTES.toSeconds(15),
                        "Average acknowledgement latency exceeding target.", "seconds", "gte"));
        return thresholds;
    }

    private Map<String, Object> threshold(Number warning,
                                          Number critical,
                                          String description,
                                          String unit,
                                          String direction) {
        Map<String, Object> map = new LinkedHashMap<>();
        if (warning != null) {
            map.put("warning", warning);
        }
        if (critical != null) {
            map.put("critical", critical);
        }
        if (unit != null) {
            map.put("unit", unit);
        }
        if (direction != null) {
            map.put("direction", direction);
        }
        if (description != null) {
            map.put("description", description);
        }
        return map;
    }
}
