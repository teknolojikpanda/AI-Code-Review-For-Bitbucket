package com.teknolojikpanda.bitbucket.aireviewer.service;

import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Constructor;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class GuardrailsTelemetryServiceTest {

    private ReviewConcurrencyController concurrencyController;
    private ReviewWorkerPool workerPool;
    private ReviewRateLimiter rateLimiter;
    private ReviewHistoryService historyService;
    private ReviewHistoryCleanupStatusService cleanupStatusService;
    private ReviewHistoryCleanupAuditService cleanupAuditService;
    private ReviewSchedulerStateService schedulerStateService;
    private GuardrailsAlertDeliveryService deliveryService;
    private GuardrailsWorkerNodeService workerNodeService;
    private GuardrailsScalingAdvisor scalingAdvisor;
    private ModelHealthService modelHealthService;
    private ReviewQueueAuditService queueAuditService;
    private GuardrailsRateLimitOverrideService overrideService;
    private GuardrailsTelemetryService telemetryService;

    @Before
    public void setUp() throws Exception {
        concurrencyController = mock(ReviewConcurrencyController.class);
        workerPool = mock(ReviewWorkerPool.class);
        rateLimiter = mock(ReviewRateLimiter.class);
        historyService = mock(ReviewHistoryService.class);
        cleanupStatusService = mock(ReviewHistoryCleanupStatusService.class);
        cleanupAuditService = mock(ReviewHistoryCleanupAuditService.class);
        schedulerStateService = mock(ReviewSchedulerStateService.class);
        deliveryService = mock(GuardrailsAlertDeliveryService.class);
        workerNodeService = mock(GuardrailsWorkerNodeService.class);
        scalingAdvisor = mock(GuardrailsScalingAdvisor.class);
        queueAuditService = mock(ReviewQueueAuditService.class);
        overrideService = mock(GuardrailsRateLimitOverrideService.class);

        ReviewSchedulerStateService.SchedulerState schedulerState =
                new ReviewSchedulerStateService.SchedulerState(
                        ReviewSchedulerStateService.SchedulerState.Mode.ACTIVE,
                        "system",
                        "System",
                        "Normal operations",
                        System.currentTimeMillis());

        ReviewConcurrencyController.QueueStats stats =
                new ReviewConcurrencyController.QueueStats(
                        4,
                        40,
                        2,
                        1,
                        System.currentTimeMillis(),
                        schedulerState,
                        3,
                        6,
                        Collections.singletonList(new ReviewConcurrencyController.QueueStats.ScopeQueueStats("proj/repo", 2, 3)),
                        Collections.singletonList(new ReviewConcurrencyController.QueueStats.ScopeQueueStats("proj", 4, 6)),
                        Collections.emptyList());
        when(concurrencyController.snapshot()).thenReturn(stats);
        when(workerPool.snapshot()).thenReturn(createWorkerSnapshot());
        when(rateLimiter.snapshot()).thenReturn(createRateSnapshot());
        when(historyService.getRecentDurationStats(anyInt())).thenReturn(createDurationStats());
        when(historyService.getRecentModelStats(anyInt())).thenReturn(ReviewHistoryService.ModelStats.empty());
        when(historyService.getRetentionStats(anyInt())).thenReturn(retentionStatsMap());

        ReviewHistoryCleanupStatusService.Status cleanupStatus =
                ReviewHistoryCleanupStatusService.Status.snapshot(true, 90, 200, 60, System.currentTimeMillis() - 5_000L, 1200L, 25, 60, null);
        when(cleanupStatusService.getStatus()).thenReturn(cleanupStatus);
        when(cleanupAuditService.listRecent(10)).thenReturn(Collections.singletonList(
                Map.of("runTimestamp", System.currentTimeMillis(), "success", true)));
        when(schedulerStateService.getState()).thenReturn(schedulerState);

        when(deliveryService.aggregateRecentDeliveries(anyInt())).thenReturn(
                new GuardrailsAlertDeliveryService.Aggregates(10, 9, 1, 2, 0.1));

        when(workerNodeService.listSnapshots()).thenReturn(Collections.singletonList(
                new GuardrailsWorkerNodeService.WorkerNodeRecord(
                        "node-1",
                        "Node One",
                        6,
                        2,
                        1,
                        6,
                        6,
                        10,
                        8,
                        System.currentTimeMillis(),
                        false)
        ));

        when(scalingAdvisor.evaluate(org.mockito.Mockito.eq(stats), org.mockito.Mockito.anyList()))
                .thenReturn(Collections.singletonList(
                        GuardrailsScalingAdvisor.ScalingHint.warning(
                                "Workers hot",
                                "Utilization > 90%",
                                "Add nodes")));
        modelHealthService = mock(ModelHealthService.class);
        when(modelHealthService.snapshot()).thenReturn(Collections.emptyMap());
        when(queueAuditService.listRecentActions(org.mockito.Mockito.anyInt())).thenReturn(Collections.singletonList(
                new ReviewConcurrencyController.QueueStats.QueueAction(
                        "canceled",
                        System.currentTimeMillis(),
                        "run-1",
                        "PRJ",
                        "repo",
                        42L,
                        false,
                        false,
                        false,
                        "admin",
                        "manual cancel",
                        "user")));
        GuardrailsRateLimitOverrideService.OverrideRecord overrideRecord =
                new GuardrailsRateLimitOverrideService.OverrideRecord(1,
                        GuardrailsRateLimitScope.PROJECT,
                        "PRJ",
                        30,
                        System.currentTimeMillis(),
                        0L,
                        "admin",
                        "Admin",
                        "reason");
        when(overrideService.listOverrides(false)).thenReturn(Collections.singletonList(overrideRecord));

        telemetryService = new GuardrailsTelemetryService(
                concurrencyController,
                workerPool,
                rateLimiter,
                historyService,
                cleanupStatusService,
                cleanupAuditService,
                schedulerStateService,
                deliveryService,
                workerNodeService,
                scalingAdvisor,
                modelHealthService,
                queueAuditService,
                overrideService);
    }

    @Test
    public void runtimeSnapshotIncludesCoreSections() {
        Map<String, Object> snapshot = telemetryService.collectRuntimeSnapshot();

        assertTrue(snapshot.containsKey("queue"));
        @SuppressWarnings("unchecked")
        Map<String, Object> queue = (Map<String, Object>) snapshot.get("queue");
        assertTrue(queue.containsKey("activeRuns"));
        assertTrue(snapshot.containsKey("workerPool"));
        assertTrue(snapshot.containsKey("workerPoolNodes"));
        assertEquals(1, ((List<?>) snapshot.get("workerPoolNodes")).size());
        assertTrue(snapshot.containsKey("rateLimiter"));
        assertTrue(snapshot.containsKey("queueActions"));
        assertTrue(snapshot.containsKey("rateLimitOverrides"));
        assertTrue(snapshot.containsKey("scalingHints"));
        assertTrue(snapshot.containsKey("retention"));
        assertTrue(snapshot.containsKey("modelStats"));
        assertTrue(snapshot.containsKey("schedulerState"));
        assertTrue(snapshot.containsKey("modelHealth"));
        @SuppressWarnings("unchecked")
        Map<String, Object> retention = (Map<String, Object>) snapshot.get("retention");
        assertTrue(retention.containsKey("schedule"));
        assertTrue(retention.containsKey("recentRuns"));
        assertEquals(1, ((List<?>) retention.get("recentRuns")).size());
    }

    @Test
    public void exportMetricsFlattensTelemetry() {
        Map<String, Object> export = telemetryService.exportMetrics();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> metrics = (List<Map<String, Object>>) export.get("metrics");

        assertTrue(metrics.stream().anyMatch(m -> "ai.queue.active".equals(m.get("name"))));
        assertTrue(metrics.stream().anyMatch(m -> "ai.retention.totalEntries".equals(m.get("name"))));
        assertTrue(metrics.stream().anyMatch(m -> "ai.worker.activeThreads".equals(m.get("name"))));
    }

    private ReviewWorkerPool.WorkerPoolSnapshot createWorkerSnapshot() throws Exception {
        Constructor<ReviewWorkerPool.WorkerPoolSnapshot> ctor =
                ReviewWorkerPool.WorkerPoolSnapshot.class.getDeclaredConstructor(
                        int.class, int.class, int.class, int.class, int.class,
                        long.class, long.class, long.class);
        ctor.setAccessible(true);
        return ctor.newInstance(6, 2, 1, 6, 6, 10L, 8L, System.currentTimeMillis());
    }

    private ReviewRateLimiter.RateLimitSnapshot createRateSnapshot() throws Exception {
        Constructor<ReviewRateLimiter.RateLimitSnapshot> ctor =
                ReviewRateLimiter.RateLimitSnapshot.class.getDeclaredConstructor(
                        int.class, int.class, int.class, int.class,
                        Map.class, Map.class, long.class);
        ctor.setAccessible(true);
        return ctor.newInstance(
                12, 24, 2, 1,
                Collections.emptyMap(),
                Collections.emptyMap(),
                System.currentTimeMillis());
    }

    private ReviewHistoryService.DurationStats createDurationStats() throws Exception {
        Constructor<ReviewHistoryService.DurationStats> ctor =
                ReviewHistoryService.DurationStats.class.getDeclaredConstructor(
                        double.class, Map.class, Map.class, int.class);
        ctor.setAccessible(true);
        return ctor.newInstance(1500D, Collections.emptyMap(), Collections.emptyMap(), 3);
    }

    private Map<String, Object> retentionStatsMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("retentionDays", 90);
        map.put("totalEntries", 120);
        map.put("entriesOlderThanRetention", 15);
        map.put("cutoffEpochMs", System.currentTimeMillis() - 90L * 24 * 60 * 60 * 1000);
        map.put("generatedAt", System.currentTimeMillis());
        return map;
    }
}
