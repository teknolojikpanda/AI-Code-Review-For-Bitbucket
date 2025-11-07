package com.teknolojikpanda.bitbucket.aireviewer.rest;

import com.atlassian.bitbucket.permission.Permission;
import com.atlassian.bitbucket.permission.PermissionService;
import com.atlassian.bitbucket.repository.Repository;
import com.atlassian.bitbucket.repository.RepositoryService;
import com.atlassian.bitbucket.user.ApplicationUser;
import com.atlassian.bitbucket.user.UserService;
import com.atlassian.plugin.spring.scanner.annotation.imports.ComponentImport;
import com.atlassian.sal.api.user.UserManager;
import com.atlassian.sal.api.user.UserProfile;
import com.teknolojikpanda.bitbucket.aireviewer.ao.AIReviewHistory;
import com.teknolojikpanda.bitbucket.aireviewer.progress.ProgressEvent;
import com.teknolojikpanda.bitbucket.aireviewer.progress.ProgressRegistry;
import com.teknolojikpanda.bitbucket.aireviewer.service.Page;
import com.teknolojikpanda.bitbucket.aireviewer.service.ReviewHistoryService;
import com.teknolojikpanda.bitbucket.aireviewer.service.ReviewSchedulerStateService;
import com.teknolojikpanda.bitbucket.aireviewer.service.ReviewConcurrencyController;

import javax.inject.Inject;
import javax.inject.Named;
import javax.servlet.http.HttpServletRequest;
import javax.annotation.Nullable;
import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.POST;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * REST endpoints exposing live and historical review progress.
 */
@Path("/progress")
@Produces(MediaType.APPLICATION_JSON)
@Named
public class ProgressResource {

    private static final int LIVE_REQUEST_LIMIT = 30;
    private static final long LIVE_WINDOW_MS = TimeUnit.SECONDS.toMillis(60);
    private static final int HISTORY_REQUEST_LIMIT = 20;
    private static final long HISTORY_WINDOW_MS = TimeUnit.SECONDS.toMillis(60);
    private static final int HISTORY_LIST_REQUEST_LIMIT = 20;
    private static final long HISTORY_LIST_WINDOW_MS = TimeUnit.SECONDS.toMillis(60);
    private static final int MAX_HISTORY_LIST_LIMIT = 50;
    private static final RateLimiter RATE_LIMITER = new RateLimiter();
    private static final int HTTP_TOO_MANY_REQUESTS = 429;

    private final UserManager userManager;
    private final UserService userService;
    private final RepositoryService repositoryService;
    private final PermissionService permissionService;
    private final ProgressRegistry progressRegistry;
    private final ReviewHistoryService reviewHistoryService;
    private final ReviewSchedulerStateService schedulerStateService;
    private final ReviewConcurrencyController concurrencyController;

    @Inject
    public ProgressResource(@ComponentImport UserManager userManager,
                            @ComponentImport UserService userService,
                            @ComponentImport RepositoryService repositoryService,
                            @ComponentImport PermissionService permissionService,
                            ProgressRegistry progressRegistry,
                            ReviewHistoryService reviewHistoryService,
                            ReviewSchedulerStateService schedulerStateService,
                            ReviewConcurrencyController concurrencyController) {
        this.userManager = Objects.requireNonNull(userManager, "userManager");
        this.userService = Objects.requireNonNull(userService, "userService");
        this.repositoryService = Objects.requireNonNull(repositoryService, "repositoryService");
        this.permissionService = Objects.requireNonNull(permissionService, "permissionService");
        this.progressRegistry = Objects.requireNonNull(progressRegistry, "progressRegistry");
        this.reviewHistoryService = Objects.requireNonNull(reviewHistoryService, "reviewHistoryService");
        this.schedulerStateService = Objects.requireNonNull(schedulerStateService, "schedulerStateService");
        this.concurrencyController = Objects.requireNonNull(concurrencyController, "concurrencyController");
    }

    @GET
    @Path("/{projectKey}/{repositorySlug}/{pullRequestId}")
    public Response getCurrent(@Context HttpServletRequest request,
                               @PathParam("projectKey") String projectKey,
                               @PathParam("repositorySlug") String repositorySlug,
                               @PathParam("pullRequestId") long pullRequestId) {
        Access access = requireRepositoryAccess(request, projectKey, repositorySlug);
        if (!access.allowed) {
            return access.response;
        }

        Response limited = enforceRateLimit(request, access.profile,
                "live:" + projectKey + "/" + repositorySlug + "/" + pullRequestId,
                LIVE_REQUEST_LIMIT,
                LIVE_WINDOW_MS,
                "progress polling requests");
        if (limited != null) {
            return limited;
        }

        Optional<ProgressRegistry.ProgressSnapshot> snapshot = progressRegistry.getActive(projectKey, repositorySlug, pullRequestId);
        if (snapshot.isEmpty()) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(error("No active review progress for the requested pull request."))
                    .build();
        }
        return Response.ok(toDto(snapshot.get())).build();
    }

    @GET
    @Path("/{projectKey}/{repositorySlug}/{pullRequestId}/history")
    public Response getRecentHistory(@Context HttpServletRequest request,
                                     @PathParam("projectKey") String projectKey,
                                     @PathParam("repositorySlug") String repositorySlug,
                                     @PathParam("pullRequestId") long pullRequestId,
                                     @QueryParam("limit") Integer limitParam,
                                     @QueryParam("offset") Integer offsetParam) {
        Access access = requireRepositoryAccess(request, projectKey, repositorySlug);
        if (!access.allowed) {
            return access.response;
        }

        Response limited = enforceRateLimit(request, access.profile,
                "history:list:" + projectKey + "/" + repositorySlug + "/" + pullRequestId,
                HISTORY_LIST_REQUEST_LIMIT,
                HISTORY_LIST_WINDOW_MS,
                "history lookup requests");
        if (limited != null) {
            return limited;
        }

        int limit = sanitizeHistoryLimit(limitParam);
        int offset = offsetParam == null ? 0 : Math.max(offsetParam, 0);

        Page<Map<String, Object>> page = reviewHistoryService.getRecentSummaries(
                projectKey,
                repositorySlug,
                pullRequestId,
                limit,
                offset);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("entries", page.getValues());
        payload.put("count", page.getValues().size());
        payload.put("total", page.getTotal());
        payload.put("limit", page.getLimit());
        payload.put("offset", page.getOffset());
        payload.put("nextOffset", computeNextOffset(page));
        payload.put("prevOffset", computePrevOffset(page));
        return Response.ok(payload).build();
    }

    @GET
    @Path("/history/{historyId}")
    public Response getHistorical(@Context HttpServletRequest request,
                                  @PathParam("historyId") long historyId) {
        Optional<AIReviewHistory> history = reviewHistoryService.findEntityById(historyId);
        if (history.isEmpty()) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(error("Review history entry not found."))
                    .build();
        }
        AIReviewHistory entity = history.get();
        Access access = requireRepositoryAccess(request, entity.getProjectKey(), entity.getRepositorySlug());
        if (!access.allowed) {
            return access.response;
        }

        Response limited = enforceRateLimit(request, access.profile,
                "history:" + historyId,
                HISTORY_REQUEST_LIMIT,
                HISTORY_WINDOW_MS,
                "progress history lookups");
        if (limited != null) {
            return limited;
        }

        Optional<Map<String, Object>> record = reviewHistoryService.getHistoryById(historyId);
        if (record.isEmpty()) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(error("Progress data not available for the requested history entry."))
                    .build();
        }

        Object progress = record.get().get("progress");
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("historyId", historyId);
        payload.put("projectKey", entity.getProjectKey());
        payload.put("repositorySlug", entity.getRepositorySlug());
        payload.put("pullRequestId", entity.getPullRequestId());
        payload.put("progress", progress);
        return Response.ok(payload).build();
    }

    @GET
    @Path("/admin/scheduler/state")
    public Response getSchedulerState(@Context HttpServletRequest request) {
        Access admin = requireSystemAdmin(request);
        if (!admin.allowed) {
            return admin.response;
        }
        return Response.ok(buildSchedulerStatePayload()).build();
    }

    @POST
    @Path("/admin/scheduler/pause")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response pauseScheduler(@Context HttpServletRequest request,
                                   SchedulerStateRequest body) {
        Access admin = requireSystemAdmin(request);
        if (!admin.allowed) {
            return admin.response;
        }
        schedulerStateService.updateState(
                ReviewSchedulerStateService.SchedulerState.Mode.PAUSED,
                userKey(admin.profile),
                admin.profile != null ? admin.profile.getFullName() : null,
                normalizeReason(body));
        return Response.ok(buildSchedulerStatePayload()).build();
    }

    @POST
    @Path("/admin/scheduler/drain")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response drainScheduler(@Context HttpServletRequest request,
                                   SchedulerStateRequest body) {
        Access admin = requireSystemAdmin(request);
        if (!admin.allowed) {
            return admin.response;
        }
        schedulerStateService.updateState(
                ReviewSchedulerStateService.SchedulerState.Mode.DRAINING,
                userKey(admin.profile),
                admin.profile != null ? admin.profile.getFullName() : null,
                normalizeReason(body));
        return Response.ok(buildSchedulerStatePayload()).build();
    }

    @POST
    @Path("/admin/scheduler/resume")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response resumeScheduler(@Context HttpServletRequest request,
                                    SchedulerStateRequest body) {
        Access admin = requireSystemAdmin(request);
        if (!admin.allowed) {
            return admin.response;
        }
        schedulerStateService.updateState(
                ReviewSchedulerStateService.SchedulerState.Mode.ACTIVE,
                userKey(admin.profile),
                admin.profile != null ? admin.profile.getFullName() : null,
                normalizeReason(body));
        return Response.ok(buildSchedulerStatePayload()).build();
    }

    private Map<String, Object> toDto(ProgressRegistry.ProgressSnapshot snapshot) {
        Map<String, Object> map = new LinkedHashMap<>();
        ProgressRegistry.ProgressMetadata meta = snapshot.getMetadata();
        map.put("projectKey", meta.getProjectKey());
        map.put("repositorySlug", meta.getRepositorySlug());
        map.put("pullRequestId", meta.getPullRequestId());
        map.put("runId", meta.getRunId());
        map.put("manual", meta.isManual());
        map.put("update", meta.isUpdate());
        map.put("force", meta.isForce());
        map.put("state", snapshot.getState());
        map.put("completed", snapshot.isCompleted());
        if (snapshot.getFinalStatus() != null) {
            map.put("finalStatus", snapshot.getFinalStatus().getValue());
        }
        map.put("startedAt", snapshot.getStartedAt());
        map.put("lastUpdatedAt", snapshot.getLastUpdatedAt());

        List<Map<String, Object>> events = snapshot.getEvents().stream()
                .map(ProgressEvent::toMap)
                .collect(Collectors.toList());
        map.put("events", events);
        map.put("eventCount", snapshot.getEventCount());
        map.put("completedAt", snapshot.getCompletedAt() > 0 ? snapshot.getCompletedAt() : null);
        map.put("summary", buildSummary(snapshot));
        return map;
    }

    private Response enforceRateLimit(HttpServletRequest request,
                                      UserProfile profile,
                                      String key,
                                      int limit,
                                      long windowMs,
                                      String actionDescription) {
        String identity = resolveIdentity(request, profile);
        String compositeKey = identity + ":" + key;
        if (!RATE_LIMITER.tryAcquire(compositeKey, limit, windowMs)) {
            long seconds = Math.max(1, TimeUnit.MILLISECONDS.toSeconds(windowMs));
            String message = String.format("Too many %s. Please wait up to %d seconds and try again.",
                    actionDescription,
                    seconds);
            return Response.status(HTTP_TOO_MANY_REQUESTS)
                    .entity(error(message))
                    .build();
        }
        return null;
    }

    private String resolveIdentity(HttpServletRequest request, UserProfile profile) {
        if (profile != null && profile.getUserKey() != null) {
            return profile.getUserKey().getStringValue();
        }
        if (request != null && request.getRemoteAddr() != null) {
            return request.getRemoteAddr();
        }
        return "anonymous";
    }

    private Access requireRepositoryAccess(HttpServletRequest request, String projectKey, String repositorySlug) {
        UserProfile profile = userManager.getRemoteUser(request);
        if (profile == null) {
            return Access.denied(Response.status(Response.Status.UNAUTHORIZED)
                    .entity(error("Authentication required")).build());
        }
        Repository repository = repositoryService.getBySlug(projectKey, repositorySlug);
        if (repository == null) {
            return Access.denied(Response.status(Response.Status.NOT_FOUND)
                    .entity(error("Repository not found")).build());
        }
        ApplicationUser user = userService.getUserBySlug(profile.getUsername());
        if (user == null) {
            return Access.denied(Response.status(Response.Status.UNAUTHORIZED)
                    .entity(error("Unable to resolve current user")).build());
        }
        boolean allowed = permissionService.hasRepositoryPermission(user, repository, Permission.REPO_READ)
                || permissionService.hasProjectPermission(user, repository.getProject(), Permission.PROJECT_READ)
                || permissionService.hasProjectPermission(user, repository.getProject(), Permission.PROJECT_ADMIN)
                || permissionService.hasRepositoryPermission(user, repository, Permission.REPO_ADMIN)
                || userManager.isSystemAdmin(profile.getUserKey());
        if (!allowed) {
            return Access.denied(Response.status(Response.Status.FORBIDDEN)
                    .entity(error("Access denied. Repository or project admin privileges required.")).build());
        }
        return Access.allowed(profile);
    }

    private Map<String, Object> error(String message) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("error", message);
        return map;
    }

    private int sanitizeHistoryLimit(Integer param) {
        if (param == null) {
            return 10;
        }
        return Math.min(Math.max(param, 1), MAX_HISTORY_LIST_LIMIT);
    }

    private Integer computeNextOffset(Page<?> page) {
        if (page.getValues().isEmpty()) {
            return null;
        }
        int next = page.getOffset() + page.getValues().size();
        return next >= page.getTotal() ? null : next;
    }

    private Integer computePrevOffset(Page<?> page) {
        if (page.getOffset() <= 0) {
            return null;
        }
        int prev = Math.max(page.getOffset() - page.getLimit(), 0);
        return prev == page.getOffset() ? null : prev;
    }

    private String buildSummary(ProgressRegistry.ProgressSnapshot snapshot) {
        List<String> parts = new ArrayList<>();
        if (snapshot.isCompleted()) {
            if (snapshot.getFinalStatus() != null) {
                parts.add(humanizeState(snapshot.getFinalStatus().getValue()));
            } else {
                parts.add("Completed");
            }
        } else {
            parts.add("Running");
        }

        long referenceTime = snapshot.isCompleted() && snapshot.getCompletedAt() > 0
                ? snapshot.getCompletedAt()
                : snapshot.getLastUpdatedAt();
        if (referenceTime > 0) {
            parts.add("Updated " + formatTimestamp(referenceTime));
        }

        int eventCount = snapshot.getEventCount();
        if (eventCount > 0) {
            parts.add(eventCount == 1 ? "1 event" : eventCount + " events");
        } else if (!snapshot.isCompleted()) {
            parts.add("Awaiting first milestone");
        }
        return String.join(" · ", parts);
    }

    private String humanizeState(String value) {
        if (value == null || value.trim().isEmpty()) {
            return "Completed";
        }
        String normalized = value.replace('_', ' ').replace('-', ' ').trim();
        String[] tokens = normalized.split("\\s+");
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < tokens.length; i++) {
            if (tokens[i].isEmpty()) {
                continue;
            }
            if (i > 0) {
                builder.append(' ');
            }
            builder.append(Character.toUpperCase(tokens[i].charAt(0)));
            if (tokens[i].length() > 1) {
                builder.append(tokens[i].substring(1).toLowerCase());
            }
        }
        return builder.length() == 0 ? "Completed" : builder.toString();
    }

    private String formatTimestamp(long epochMillis) {
        try {
            return DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(
                    Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()));
        } catch (Exception ex) {
            return Long.toString(epochMillis);
        }
    }

    private Map<String, Object> buildSchedulerStatePayload() {
        ReviewSchedulerStateService.SchedulerState state = schedulerStateService.getState();
        ReviewConcurrencyController.QueueStats stats = concurrencyController.snapshot();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("state", schedulerStateToMap(state));
        payload.put("queue", queueSnapshotToMap(stats));
        return payload;
    }

    private Map<String, Object> queueSnapshotToMap(ReviewConcurrencyController.QueueStats stats) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("maxConcurrent", stats.getMaxConcurrent());
        map.put("maxQueued", stats.getMaxQueued());
        map.put("active", stats.getActive());
        map.put("waiting", stats.getWaiting());
        map.put("capturedAt", stats.getCapturedAt());
        map.put("schedulerState", schedulerStateToMap(stats.getSchedulerState()));
        return map;
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

    private Access requireSystemAdmin(HttpServletRequest request) {
        UserProfile profile = userManager.getRemoteUser(request);
        if (profile == null) {
            return Access.denied(Response.status(Response.Status.UNAUTHORIZED)
                    .entity(error("Authentication required")).build());
        }
        if (!userManager.isSystemAdmin(profile.getUserKey())) {
            return Access.denied(Response.status(Response.Status.FORBIDDEN)
                    .entity(error("Administrator privileges required.")).build());
        }
        return Access.allowed(profile);
    }

    private String normalizeReason(SchedulerStateRequest request) {
        if (request == null || request.reason == null) {
            return null;
        }
        String trimmed = request.reason.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String userKey(@Nullable UserProfile profile) {
        if (profile == null || profile.getUserKey() == null) {
            return null;
        }
        return profile.getUserKey().getStringValue();
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

    private static final class SchedulerStateRequest {
        public String reason;
    }

    private static final class RateLimiter {
        private final ConcurrentMap<String, Window> windows = new ConcurrentHashMap<>();

        boolean tryAcquire(String key, int limit, long windowMs) {
            long now = System.currentTimeMillis();
            Window window = windows.computeIfAbsent(key, k -> new Window(now));
            synchronized (window) {
                if (now - window.windowStart >= windowMs) {
                    window.windowStart = now;
                    window.count.set(0);
                }
                return window.count.incrementAndGet() <= limit;
            }
        }

        private static final class Window {
            volatile long windowStart;
            final AtomicInteger count = new AtomicInteger();

            Window(long windowStart) {
                this.windowStart = windowStart;
            }
        }
    }
}
