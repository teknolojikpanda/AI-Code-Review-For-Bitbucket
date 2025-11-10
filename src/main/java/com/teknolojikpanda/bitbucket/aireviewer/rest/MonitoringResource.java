package com.teknolojikpanda.bitbucket.aireviewer.rest;

import com.atlassian.plugin.spring.scanner.annotation.imports.ComponentImport;
import com.atlassian.sal.api.user.UserManager;
import com.atlassian.sal.api.user.UserProfile;
import com.teknolojikpanda.bitbucket.aireviewer.service.ReviewConcurrencyController;
import com.teknolojikpanda.bitbucket.aireviewer.service.ReviewHistoryService;
import com.teknolojikpanda.bitbucket.aireviewer.service.ReviewRateLimiter;
import com.teknolojikpanda.bitbucket.aireviewer.service.ReviewSchedulerStateService;
import com.teknolojikpanda.bitbucket.aireviewer.service.ReviewWorkerPool;

import javax.inject.Inject;
import javax.inject.Named;
import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static javax.ws.rs.core.Response.Status.FORBIDDEN;
import static javax.ws.rs.core.Response.Status.UNAUTHORIZED;

@Path("/monitoring")
@Produces(MediaType.APPLICATION_JSON)
@Named
public class MonitoringResource {

    private final UserManager userManager;
    private final ReviewConcurrencyController concurrencyController;
    private final ReviewWorkerPool workerPool;
    private final ReviewRateLimiter rateLimiter;
    private final ReviewHistoryService historyService;
    private final ReviewSchedulerStateService schedulerStateService;

    @Inject
    public MonitoringResource(@ComponentImport UserManager userManager,
                              ReviewConcurrencyController concurrencyController,
                              ReviewWorkerPool workerPool,
                              ReviewRateLimiter rateLimiter,
                              ReviewHistoryService historyService,
                              ReviewSchedulerStateService schedulerStateService) {
        this.userManager = Objects.requireNonNull(userManager, "userManager");
        this.concurrencyController = Objects.requireNonNull(concurrencyController, "concurrencyController");
        this.workerPool = Objects.requireNonNull(workerPool, "workerPool");
        this.rateLimiter = Objects.requireNonNull(rateLimiter, "rateLimiter");
        this.historyService = Objects.requireNonNull(historyService, "historyService");
        this.schedulerStateService = Objects.requireNonNull(schedulerStateService, "schedulerStateService");
    }

    @GET
    @Path("/runtime")
    public Response getRuntime(@Context HttpServletRequest request) {
        Access access = requireSystemAdmin(request);
        if (!access.allowed) {
            return access.response;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        ReviewConcurrencyController.QueueStats queueStats = concurrencyController.snapshot();
        payload.put("queue", queueSnapshotToMap(queueStats));
        payload.put("schedulerState", schedulerStateToMap(queueStats.getSchedulerState()));
        payload.put("workerPool", workerPoolStatsToMap(workerPool.snapshot()));
        payload.put("rateLimiter", rateLimitStatsToMap(rateLimiter.snapshot()));
        payload.put("reviewDurations", durationStatsToMap(historyService.getRecentDurationStats(200)));
        return Response.ok(payload).build();
    }

    private Map<String, Object> queueSnapshotToMap(ReviewConcurrencyController.QueueStats stats) {
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
        return map;
    }

    private Map<String, Object> workerPoolStatsToMap(ReviewWorkerPool.WorkerPoolSnapshot snapshot) {
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

    private Map<String, Object> rateLimitStatsToMap(ReviewRateLimiter.RateLimitSnapshot snapshot) {
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

    private Map<String, Object> durationStatsToMap(ReviewHistoryService.DurationStats stats) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("samples", stats.getSamples());
        Map<String, Object> averages = new LinkedHashMap<>();
        averages.put("globalMs", stats.estimate(null, null, 0));
        map.put("averages", averages);
        return map;
    }

    private List<Map<String, Object>> bucketStatesToList(Map<String, ReviewRateLimiter.RateLimitSnapshot.BucketState> states) {
        if (states == null || states.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> list = new ArrayList<>();
        states.forEach((scope, state) -> {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("scope", scope);
            map.put("consumed", state.getConsumed());
            map.put("limit", state.getLimit());
            map.put("remaining", state.getRemaining());
            map.put("resetInMs", state.getResetInMs());
            list.add(map);
        });
        return list;
    }

    private Map<String, Object> schedulerStateToMap(ReviewSchedulerStateService.SchedulerState state) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("mode", state.getMode().name());
        map.put("reason", state.getReason());
        map.put("updatedBy", state.getUpdatedBy());
        map.put("updatedByDisplayName", state.getUpdatedByDisplayName());
        map.put("updatedAt", state.getUpdatedAt());
        return map;
    }

    private List<Map<String, Object>> scopeStatsToList(List<ReviewConcurrencyController.QueueStats.ScopeQueueStats> stats) {
        if (stats == null || stats.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> list = new ArrayList<>();
        for (ReviewConcurrencyController.QueueStats.ScopeQueueStats scope : stats) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("scope", scope.getScope());
            map.put("waiting", scope.getWaiting());
            map.put("limit", scope.getLimit());
            list.add(map);
        }
        return list;
    }

    private Access requireSystemAdmin(HttpServletRequest request) {
        UserProfile profile = userManager.getRemoteUser(request);
        if (profile == null) {
            return Access.denied(Response.status(UNAUTHORIZED)
                    .entity(Map.of("error", "Authentication required")).build());
        }
        if (!userManager.isSystemAdmin(profile.getUserKey())) {
            return Access.denied(Response.status(FORBIDDEN)
                    .entity(Map.of("error", "Administrator privileges required")).build());
        }
        return Access.allowed(profile);
    }

    private static final class Access {
        final boolean allowed;
        final Response response;
        final UserProfile profile;

        private Access(boolean allowed, Response response, UserProfile profile) {
            this.allowed = allowed;
            this.response = response;
            this.profile = profile;
        }

        static Access allowed(UserProfile profile) {
            return new Access(true, null, profile);
        }

        static Access denied(Response response) {
            return new Access(false, response, null);
        }
    }
}
