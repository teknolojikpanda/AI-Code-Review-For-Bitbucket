package com.teknolojikpanda.bitbucket.aireviewer.rest;

import com.atlassian.sal.api.user.UserKey;
import com.atlassian.sal.api.user.UserManager;
import com.atlassian.sal.api.user.UserProfile;
import com.teknolojikpanda.bitbucket.aireviewer.progress.ProgressRegistry;
import com.teknolojikpanda.bitbucket.aireviewer.service.ReviewConcurrencyController;
import com.teknolojikpanda.bitbucket.aireviewer.service.ReviewHistoryCleanupAuditService;
import com.teknolojikpanda.bitbucket.aireviewer.service.ReviewHistoryCleanupScheduler;
import com.teknolojikpanda.bitbucket.aireviewer.service.ReviewHistoryCleanupAuditService;
import com.teknolojikpanda.bitbucket.aireviewer.service.ReviewHistoryCleanupService;
import com.teknolojikpanda.bitbucket.aireviewer.service.ReviewHistoryCleanupStatusService;
import com.teknolojikpanda.bitbucket.aireviewer.service.ReviewHistoryService;
import com.teknolojikpanda.bitbucket.aireviewer.service.ReviewRateLimiter;
import com.teknolojikpanda.bitbucket.aireviewer.service.ReviewWorkerPool;
import com.teknolojikpanda.bitbucket.aireviewer.service.ReviewSchedulerStateService;
import org.junit.Before;
import org.junit.Test;

import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.core.Response;
import java.lang.reflect.Constructor;
import java.util.Collections;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

public class HistoryResourceTest {

    private UserManager userManager;
    private ReviewHistoryService historyService;
    private HttpServletRequest request;
    private UserProfile profile;
    private ProgressRegistry progressRegistry;
    private ReviewConcurrencyController concurrencyController;
    private ReviewRateLimiter rateLimiter;
    private ReviewWorkerPool workerPool;
    private ReviewHistoryCleanupService cleanupService;
    private ReviewHistoryCleanupStatusService cleanupStatusService;
    private ReviewHistoryCleanupAuditService cleanupAuditService;
    private ReviewHistoryCleanupScheduler cleanupScheduler;
    private HistoryResource resource;

    @Before
    public void setUp() {
        System.setProperty("javax.ws.rs.ext.RuntimeDelegate", "com.sun.jersey.server.impl.provider.RuntimeDelegateImpl");
        userManager = mock(UserManager.class);
        historyService = mock(ReviewHistoryService.class);
        when(historyService.getRetentionStats(anyInt())).thenReturn(new java.util.LinkedHashMap<>());
        progressRegistry = mock(ProgressRegistry.class);
        concurrencyController = mock(ReviewConcurrencyController.class);
        rateLimiter = mock(ReviewRateLimiter.class);
        workerPool = mock(ReviewWorkerPool.class);
        cleanupService = mock(ReviewHistoryCleanupService.class);
        cleanupStatusService = mock(ReviewHistoryCleanupStatusService.class);
        cleanupAuditService = mock(ReviewHistoryCleanupAuditService.class);
        cleanupScheduler = mock(ReviewHistoryCleanupScheduler.class);
        request = mock(HttpServletRequest.class);
        profile = mock(UserProfile.class);
        UserKey adminKey = new UserKey("admin");
        when(profile.getUserKey()).thenReturn(adminKey);
        when(profile.getFullName()).thenReturn("Admin");
        ReviewSchedulerStateService.SchedulerState schedulerState =
                new ReviewSchedulerStateService.SchedulerState(
                        ReviewSchedulerStateService.SchedulerState.Mode.ACTIVE,
                        "admin",
                        "Admin",
                        "All good",
                        System.currentTimeMillis());
        ReviewConcurrencyController.QueueStats stats =
                new ReviewConcurrencyController.QueueStats(
                        2,
                        25,
                        0,
                        0,
                        System.currentTimeMillis(),
                        schedulerState,
                        5,
                        15,
                        Collections.emptyList(),
                        Collections.emptyList());
        when(concurrencyController.snapshot()).thenReturn(stats);
        ReviewRateLimiter.RateLimitSnapshot rateSnapshot = createRateLimitSnapshot();
        when(rateLimiter.snapshot(anyInt())).thenReturn(rateSnapshot);
        ReviewWorkerPool.WorkerPoolSnapshot workerSnapshot = createWorkerPoolSnapshot();
        when(workerPool.snapshot()).thenReturn(workerSnapshot);
        ReviewHistoryCleanupStatusService.Status cleanupStatus =
                ReviewHistoryCleanupStatusService.Status.snapshot(true, 90, 200, 1440, 0L, 0L, 0, 0, null);
        when(cleanupStatusService.getStatus()).thenReturn(cleanupStatus);
        when(cleanupAuditService.listRecent(anyInt())).thenReturn(Collections.emptyList());
        resource = new HistoryResource(userManager, historyService, progressRegistry, concurrencyController, rateLimiter, workerPool, cleanupService, cleanupStatusService, cleanupAuditService, cleanupScheduler);
    }

    private ReviewRateLimiter.RateLimitSnapshot createRateLimitSnapshot() {
        try {
            Constructor<ReviewRateLimiter.RateLimitSnapshot> ctor =
                    ReviewRateLimiter.RateLimitSnapshot.class.getDeclaredConstructor(
                            int.class, int.class, int.class, int.class, Map.class, Map.class, long.class);
            ctor.setAccessible(true);
            return ctor.newInstance(
                    12, 60, 2, 1, Collections.emptyMap(), Collections.emptyMap(), System.currentTimeMillis());
        } catch (Exception e) {
            throw new AssertionError("Failed to construct RateLimitSnapshot for tests", e);
        }
    }

    private ReviewWorkerPool.WorkerPoolSnapshot createWorkerPoolSnapshot() {
        try {
            Constructor<ReviewWorkerPool.WorkerPoolSnapshot> ctor =
                    ReviewWorkerPool.WorkerPoolSnapshot.class.getDeclaredConstructor(
                            int.class, int.class, int.class, int.class, int.class, long.class, long.class, long.class);
            ctor.setAccessible(true);
            return ctor.newInstance(
                    4, 1, 0, 4, 4, 10L, 9L, System.currentTimeMillis());
        } catch (Exception e) {
            throw new AssertionError("Failed to construct WorkerPoolSnapshot for tests", e);
        }
    }

    @Test
    public void backfillChunkTelemetryDeniedForNonAdmin() {
        when(userManager.getRemoteUser(request)).thenReturn(profile);
        when(userManager.isSystemAdmin(profile.getUserKey())).thenReturn(false);

        Response response = resource.backfillChunkTelemetry(request, 100);

        assertEquals(Response.Status.FORBIDDEN.getStatusCode(), response.getStatus());
        @SuppressWarnings("unchecked")
        Map<String, String> payload = (Map<String, String>) response.getEntity();
        assertEquals("Access denied. Administrator privileges required.", payload.get("error"));
        verify(historyService, never()).backfillChunkTelemetry(anyInt());
    }

    @Test
    public void backfillChunkTelemetryDefaultsLimitForAdmin() {
        when(userManager.getRemoteUser(request)).thenReturn(profile);
        when(userManager.isSystemAdmin(profile.getUserKey())).thenReturn(true);
        Map<String, Object> result = Collections.singletonMap("chunksCreated", 3);
        when(historyService.backfillChunkTelemetry(eq(0))).thenReturn(result);

        Response response = resource.backfillChunkTelemetry(request, null);

        assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) response.getEntity();
        assertSame(result, payload);
        verify(historyService).backfillChunkTelemetry(0);
    }

    @Test
    public void backfillChunkTelemetryPassesThroughProvidedLimit() {
        when(userManager.getRemoteUser(request)).thenReturn(profile);
        when(userManager.isSystemAdmin(profile.getUserKey())).thenReturn(true);
        Map<String, Object> result = Collections.singletonMap("historiesUpdated", 2);
        when(historyService.backfillChunkTelemetry(eq(25))).thenReturn(result);

        Response response = resource.backfillChunkTelemetry(request, 25);

        assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) response.getEntity();
        assertSame(result, payload);
        verify(historyService).backfillChunkTelemetry(25);
    }

    @Test
    public void cleanupDeniedForNonAdmin() {
        when(userManager.getRemoteUser(request)).thenReturn(profile);
        when(userManager.isSystemAdmin(profile.getUserKey())).thenReturn(false);

        Response response = resource.cleanupHistory(request, new HistoryResource.CleanupRequest());

        assertEquals(Response.Status.FORBIDDEN.getStatusCode(), response.getStatus());
        verify(cleanupService, never()).cleanupOlderThanDays(anyInt(), anyInt());
        verify(cleanupStatusService, never()).updateSchedule(anyInt(), anyInt(), anyInt(), anyBoolean());
        verify(cleanupScheduler, never()).reschedule();
    }

    @Test
    public void cleanupExecutesForAdmin() {
        when(userManager.getRemoteUser(request)).thenReturn(profile);
        when(userManager.isSystemAdmin(profile.getUserKey())).thenReturn(true);
        ReviewHistoryCleanupService.CleanupResult result =
                new ReviewHistoryCleanupService.CleanupResult(60, 100, 5, 20, 10, System.currentTimeMillis());
        when(cleanupService.cleanupOlderThanDays(eq(60), eq(100))).thenReturn(result);
        HistoryResource.CleanupRequest body = new HistoryResource.CleanupRequest();
        body.retentionDays = 60;
        body.batchSize = 100;
        body.intervalMinutes = 180;
        body.enabled = true;
        body.runNow = true;

        Response response = resource.cleanupHistory(request, body);

        assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
        verify(cleanupService).cleanupOlderThanDays(60, 100);
        verify(cleanupStatusService).updateSchedule(60, 100, 180, true);
        verify(cleanupScheduler).reschedule();
        verify(cleanupStatusService).recordRun(any(), anyLong());
    }

    @Test
    public void metricsSummaryDeniedForNonAdmin() {
        when(userManager.getRemoteUser(request)).thenReturn(profile);
        when(userManager.isSystemAdmin(profile.getUserKey())).thenReturn(false);

        Response response = resource.getMetrics(request, "WOR", "repo", 5L, null, null);

        assertEquals(Response.Status.FORBIDDEN.getStatusCode(), response.getStatus());
        @SuppressWarnings("unchecked")
        Map<String, String> payload = (Map<String, String>) response.getEntity();
        assertEquals("Access denied. Administrator privileges required.", payload.get("error"));
        verify(historyService, never()).getMetricsSummary(any(), any(), any(), any(), any());
    }

    @Test
    public void metricsSummaryReturnsAggregatedPayloadForAdmin() {
        when(userManager.getRemoteUser(request)).thenReturn(profile);
        when(userManager.isSystemAdmin(profile.getUserKey())).thenReturn(true);
        Map<String, Object> summary = new java.util.LinkedHashMap<>();
        summary.put("ioTotals", Collections.singletonMap("chunkCount", 4L));
        when(historyService.getMetricsSummary(eq("WOR"), eq("repo"), eq(7L), isNull(), isNull()))
                .thenReturn(summary);

        Response response = resource.getMetrics(request, "WOR", "repo", 7L, -5L, 0L);

        assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) response.getEntity();
        assertSame(summary, payload);
        verify(historyService).getMetricsSummary(eq("WOR"), eq("repo"), eq(7L), isNull(), isNull());
    }

    @Test
    public void cleanupHistoryRunsImmediatelyWhenRequested() {
        when(userManager.getRemoteUser(request)).thenReturn(profile);
        when(userManager.isSystemAdmin(profile.getUserKey())).thenReturn(true);
        ReviewHistoryCleanupStatusService.Status current =
                ReviewHistoryCleanupStatusService.Status.snapshot(true, 90, 200, 1440, 0L, 0L, 0, 0, null);
        ReviewHistoryCleanupStatusService.Status updated =
                ReviewHistoryCleanupStatusService.Status.snapshot(true, 60, 500, 30, System.currentTimeMillis(), 2500L, 12, 24, null);
        when(cleanupStatusService.getStatus()).thenReturn(current, updated);
        ReviewHistoryCleanupService.CleanupResult result =
                new ReviewHistoryCleanupService.CleanupResult(60, 500, 3, 12, 5, System.currentTimeMillis());
        when(cleanupService.cleanupOlderThanDays(60, 500)).thenReturn(result);

        HistoryResource.CleanupRequest body = new HistoryResource.CleanupRequest();
        body.retentionDays = 60;
        body.batchSize = 500;
        body.intervalMinutes = 30;
        body.enabled = true;
        body.runNow = true;

        Response response = resource.cleanupHistory(request, body);

        assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
        verify(cleanupStatusService).updateSchedule(60, 500, 30, true);
        verify(cleanupScheduler).reschedule();
        verify(cleanupService).cleanupOlderThanDays(60, 500);
        verify(cleanupStatusService).recordRun(eq(result), anyLong());
        verify(cleanupAuditService).recordRun(anyLong(), anyLong(), eq(3), eq(12), eq(true), eq("admin"), eq("Admin"));

        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) response.getEntity();
        assertTrue(payload.containsKey("result"));
        assertTrue(payload.containsKey("status"));
        assertTrue(payload.containsKey("recentRuns"));
    }

    @Test
    public void cleanupHistorySkipsImmediateRunWhenNotRequested() {
        when(userManager.getRemoteUser(request)).thenReturn(profile);
        when(userManager.isSystemAdmin(profile.getUserKey())).thenReturn(true);
        ReviewHistoryCleanupStatusService.Status status =
                ReviewHistoryCleanupStatusService.Status.snapshot(false, 45, 100, 60, 0L, 0L, 0, 0, "recent failure");
        when(cleanupStatusService.getStatus()).thenReturn(status, status);

        HistoryResource.CleanupRequest body = new HistoryResource.CleanupRequest();
        body.retentionDays = 45;
        body.batchSize = 100;
        body.intervalMinutes = 60;
        body.enabled = false;
        body.runNow = false;

        Response response = resource.cleanupHistory(request, body);

        assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
        verify(cleanupStatusService).updateSchedule(45, 100, 60, false);
        verify(cleanupScheduler).reschedule();
        verify(cleanupService, never()).cleanupOlderThanDays(anyInt(), anyInt());
        verify(cleanupAuditService, never()).recordRun(anyLong(), anyLong(), anyInt(), anyInt(), anyBoolean(), any(), any());

        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) response.getEntity();
        assertTrue(payload.containsKey("status"));
        assertTrue(!payload.containsKey("result"));
        assertTrue(payload.containsKey("recentRuns"));
    }
}
