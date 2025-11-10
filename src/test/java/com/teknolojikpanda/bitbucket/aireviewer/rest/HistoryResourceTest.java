package com.teknolojikpanda.bitbucket.aireviewer.rest;

import com.atlassian.sal.api.user.UserManager;
import com.atlassian.sal.api.user.UserProfile;
import com.teknolojikpanda.bitbucket.aireviewer.progress.ProgressRegistry;
import com.teknolojikpanda.bitbucket.aireviewer.service.ReviewConcurrencyController;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
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
    private HistoryResource resource;

    @Before
    public void setUp() {
        System.setProperty("javax.ws.rs.ext.RuntimeDelegate", "com.sun.jersey.server.impl.provider.RuntimeDelegateImpl");
        userManager = mock(UserManager.class);
        historyService = mock(ReviewHistoryService.class);
        when(historyService.getRetentionStats(anyInt())).thenReturn(Collections.emptyMap());
        progressRegistry = mock(ProgressRegistry.class);
        concurrencyController = mock(ReviewConcurrencyController.class);
        rateLimiter = mock(ReviewRateLimiter.class);
        workerPool = mock(ReviewWorkerPool.class);
        request = mock(HttpServletRequest.class);
        profile = mock(UserProfile.class);
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
        resource = new HistoryResource(userManager, historyService, progressRegistry, concurrencyController, rateLimiter, workerPool);
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
}
