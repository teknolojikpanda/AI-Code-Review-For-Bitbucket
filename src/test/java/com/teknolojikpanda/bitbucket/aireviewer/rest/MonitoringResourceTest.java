package com.teknolojikpanda.bitbucket.aireviewer.rest;

import com.atlassian.sal.api.user.UserKey;
import com.atlassian.sal.api.user.UserManager;
import com.atlassian.sal.api.user.UserProfile;
import com.teknolojikpanda.bitbucket.aireviewer.service.ReviewConcurrencyController;
import com.teknolojikpanda.bitbucket.aireviewer.service.ReviewHistoryService;
import com.teknolojikpanda.bitbucket.aireviewer.service.ReviewRateLimiter;
import com.teknolojikpanda.bitbucket.aireviewer.service.ReviewSchedulerStateService;
import com.teknolojikpanda.bitbucket.aireviewer.service.ReviewWorkerPool;
import org.junit.Before;
import org.junit.Test;

import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.core.Response;
import java.util.Collections;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class MonitoringResourceTest {

    private UserManager userManager;
    private ReviewConcurrencyController concurrencyController;
    private ReviewWorkerPool workerPool;
    private ReviewRateLimiter rateLimiter;
    private ReviewHistoryService historyService;
    private ReviewSchedulerStateService schedulerStateService;
    private MonitoringResource resource;
    private HttpServletRequest request;
    private UserProfile profile;

    @Before
    public void setUp() {
        userManager = mock(UserManager.class);
        concurrencyController = mock(ReviewConcurrencyController.class);
        workerPool = mock(ReviewWorkerPool.class);
        rateLimiter = mock(ReviewRateLimiter.class);
        historyService = mock(ReviewHistoryService.class);
        schedulerStateService = mock(ReviewSchedulerStateService.class);
        request = mock(HttpServletRequest.class);
        profile = mock(UserProfile.class);

        ReviewSchedulerStateService.SchedulerState schedulerState =
                new ReviewSchedulerStateService.SchedulerState(
                        ReviewSchedulerStateService.SchedulerState.Mode.ACTIVE,
                        "system",
                        "System",
                        null,
                        System.currentTimeMillis());
        ReviewConcurrencyController.QueueStats stats =
                new ReviewConcurrencyController.QueueStats(
                        2,
                        25,
                        1,
                        0,
                        System.currentTimeMillis(),
                        schedulerState,
                        5,
                        15,
                        Collections.emptyList(),
                        Collections.emptyList());
        when(concurrencyController.snapshot()).thenReturn(stats);
        when(workerPool.snapshot()).thenReturn(createWorkerSnapshot());
        when(rateLimiter.snapshot()).thenReturn(createRateSnapshot());
        when(historyService.getRecentDurationStats(anyInt())).thenReturn(ReviewHistoryService.DurationStats.empty());

        resource = new MonitoringResource(userManager, concurrencyController, workerPool, rateLimiter, historyService, schedulerStateService);
    }

    @Test
    public void getRuntimeRequiresAuthentication() {
        when(userManager.getRemoteUser(request)).thenReturn(null);

        Response response = resource.getRuntime(request);

        assertEquals(Response.Status.UNAUTHORIZED.getStatusCode(), response.getStatus());
    }

    @Test
    public void getRuntimeRequiresAdmin() {
        when(userManager.getRemoteUser(request)).thenReturn(profile);
        UserKey key = new UserKey("admin");
        when(profile.getUserKey()).thenReturn(key);
        when(userManager.isSystemAdmin(key)).thenReturn(false);

        Response response = resource.getRuntime(request);

        assertEquals(Response.Status.FORBIDDEN.getStatusCode(), response.getStatus());
    }

    @Test
    public void getRuntimeReturnsTelemetry() {
        when(userManager.getRemoteUser(request)).thenReturn(profile);
        UserKey key = new UserKey("admin");
        when(profile.getUserKey()).thenReturn(key);
        when(userManager.isSystemAdmin(key)).thenReturn(true);

        Response response = resource.getRuntime(request);

        assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> payload = (java.util.Map<String, Object>) response.getEntity();
        assertTrue(payload.containsKey("queue"));
        assertTrue(payload.containsKey("workerPool"));
        assertTrue(payload.containsKey("rateLimiter"));
        assertTrue(payload.containsKey("reviewDurations"));
    }

    private ReviewWorkerPool.WorkerPoolSnapshot createWorkerSnapshot() {
        try {
            java.lang.reflect.Constructor<ReviewWorkerPool.WorkerPoolSnapshot> ctor =
                    ReviewWorkerPool.WorkerPoolSnapshot.class.getDeclaredConstructor(
                            int.class, int.class, int.class, int.class, int.class,
                            long.class, long.class, long.class);
            ctor.setAccessible(true);
            return ctor.newInstance(
                    4, 1, 0, 4, 4,
                    5L, 4L, System.currentTimeMillis());
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    private ReviewRateLimiter.RateLimitSnapshot createRateSnapshot() {
        try {
            java.lang.reflect.Constructor<ReviewRateLimiter.RateLimitSnapshot> ctor =
                    ReviewRateLimiter.RateLimitSnapshot.class.getDeclaredConstructor(
                            int.class, int.class, int.class, int.class,
                            Map.class, Map.class, long.class);
            ctor.setAccessible(true);
            return ctor.newInstance(
                    10, 20, 1, 1,
                    Collections.emptyMap(),
                    Collections.emptyMap(),
                    System.currentTimeMillis());
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }
}
