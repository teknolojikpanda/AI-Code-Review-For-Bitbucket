package com.teknolojikpanda.bitbucket.aireviewer.rest;

import com.atlassian.plugin.spring.scanner.annotation.imports.ComponentImport;
import com.atlassian.sal.api.user.UserManager;
import com.atlassian.sal.api.user.UserProfile;
import com.teknolojikpanda.bitbucket.aireviewer.progress.ProgressEvent;
import com.teknolojikpanda.bitbucket.aireviewer.progress.ProgressRegistry;
import com.teknolojikpanda.bitbucket.aireviewer.service.Page;
import com.teknolojikpanda.bitbucket.aireviewer.service.ReviewConcurrencyController;
import com.teknolojikpanda.bitbucket.aireviewer.service.ReviewHistoryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.inject.Named;
import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * REST resource exposing AI review history for administrators.
 */
@Path("/history")
@Named
public class HistoryResource {

    private static final Logger log = LoggerFactory.getLogger(HistoryResource.class);

    private final UserManager userManager;
    private final ReviewHistoryService historyService;
    private final ProgressRegistry progressRegistry;
    private final ReviewConcurrencyController concurrencyController;

    @Inject
    public HistoryResource(@ComponentImport UserManager userManager,
                           ReviewHistoryService historyService,
                           ProgressRegistry progressRegistry,
                           ReviewConcurrencyController concurrencyController) {
        this.userManager = userManager;
        this.historyService = historyService;
        this.progressRegistry = progressRegistry;
        this.concurrencyController = concurrencyController;
    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public Response listHistory(@Context HttpServletRequest request,
                                @QueryParam("projectKey") String projectKey,
                                @QueryParam("repositorySlug") String repositorySlug,
                                @QueryParam("pullRequestId") Long pullRequestId,
                                @QueryParam("since") Long sinceParam,
                                @QueryParam("until") Long untilParam,
                                @QueryParam("limit") Integer limitParam,
                                @QueryParam("offset") Integer offsetParam) {
        UserProfile profile = userManager.getRemoteUser(request);
        if (!isSystemAdmin(profile)) {
            return Response.status(Response.Status.FORBIDDEN)
                    .entity(error("Access denied. Administrator privileges required."))
                    .build();
        }

        int limit = (limitParam == null) ? 20 : limitParam;
        int offset = (offsetParam == null) ? 0 : offsetParam;
        Long since = sanitizeEpoch(sinceParam);
        Long until = sanitizeEpoch(untilParam);
        try {
            Page<Map<String, Object>> page = historyService.getHistory(
                    projectKey,
                    repositorySlug,
                    pullRequestId,
                    since,
                    until,
                    limit,
                    offset);
            List<Map<String, Object>> ongoing = collectOngoingEntries(
                    projectKey,
                    repositorySlug,
                    pullRequestId,
                    since,
                    until);
            Map<String, Object> payload = new HashMap<>();
            payload.put("entries", page.getValues());
            payload.put("count", page.getValues().size());
            payload.put("total", page.getTotal());
            payload.put("limit", page.getLimit());
            payload.put("offset", page.getOffset());
            payload.put("nextOffset", computeNextOffset(page));
            payload.put("prevOffset", computePrevOffset(page));
            payload.put("queueStats", queueStatsToMap());
            payload.put("ongoing", ongoing);
            payload.put("ongoingCount", ongoing.size());
            return Response.ok(payload).build();
        } catch (Exception ex) {
            log.error("Failed to fetch AI review history", ex);
            return Response.serverError()
                    .entity(error("Failed to fetch review history: " + ex.getMessage()))
                    .build();
        }
    }

    @GET
    @Path("/metrics")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getMetrics(@Context HttpServletRequest request,
                               @QueryParam("projectKey") String projectKey,
                               @QueryParam("repositorySlug") String repositorySlug,
                               @QueryParam("pullRequestId") Long pullRequestId,
                               @QueryParam("since") Long sinceParam,
                               @QueryParam("until") Long untilParam) {
        UserProfile profile = userManager.getRemoteUser(request);
        if (!isSystemAdmin(profile)) {
            return Response.status(Response.Status.FORBIDDEN)
                    .entity(error("Access denied. Administrator privileges required."))
                    .build();
        }

        Long since = sanitizeEpoch(sinceParam);
        Long until = sanitizeEpoch(untilParam);

        try {
            Map<String, Object> summary = historyService.getMetricsSummary(
                    projectKey,
                    repositorySlug,
                    pullRequestId,
                    since,
                    until);
            return Response.ok(summary).build();
        } catch (Exception ex) {
            log.error("Failed to compute AI review metrics", ex);
            return Response.serverError()
                    .entity(error("Failed to compute metrics: " + ex.getMessage()))
                    .build();
        }
    }

    @GET
    @Path("/metrics/daily")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getMetricsDaily(@Context HttpServletRequest request,
                                    @QueryParam("projectKey") String projectKey,
                                    @QueryParam("repositorySlug") String repositorySlug,
                                    @QueryParam("pullRequestId") Long pullRequestId,
                                    @QueryParam("since") Long sinceParam,
                                    @QueryParam("until") Long untilParam,
                                    @QueryParam("limit") Integer limitParam) {
        UserProfile profile = userManager.getRemoteUser(request);
        if (!isSystemAdmin(profile)) {
            return Response.status(Response.Status.FORBIDDEN)
                    .entity(error("Access denied. Administrator privileges required."))
                    .build();
        }

        Long since = sanitizeEpoch(sinceParam);
        Long until = sanitizeEpoch(untilParam);
        int limit = (limitParam == null || limitParam <= 0) ? 30 : limitParam;

        try {
            Map<String, Object> payload = new HashMap<>();
            payload.put("days", historyService.getDailySummary(
                    projectKey,
                    repositorySlug,
                    pullRequestId,
                    since,
                    until,
                    limit));
            return Response.ok(payload).build();
        } catch (Exception ex) {
            log.error("Failed to compute daily AI review metrics", ex);
            return Response.serverError()
                    .entity(error("Failed to compute daily metrics: " + ex.getMessage()))
                    .build();
        }
    }

    @POST
    @Path("/backfill/chunks")
    @Produces(MediaType.APPLICATION_JSON)
    public Response backfillChunkTelemetry(@Context HttpServletRequest request,
                                           @QueryParam("limit") Integer limitParam) {
        UserProfile profile = userManager.getRemoteUser(request);
        if (!isSystemAdmin(profile)) {
            return Response.status(Response.Status.FORBIDDEN)
                    .entity(error("Access denied. Administrator privileges required."))
                    .build();
        }

        int limit = (limitParam == null) ? 0 : limitParam;
        Map<String, Object> result = historyService.backfillChunkTelemetry(limit);
        return Response.ok(result).build();
    }

    private boolean isSystemAdmin(UserProfile profile) {
        return profile != null && userManager.isSystemAdmin(profile.getUserKey());
    }

    private Long sanitizeEpoch(Long value) {
        return (value == null || value <= 0) ? null : value;
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

    private List<Map<String, Object>> collectOngoingEntries(String projectKey,
                                                            String repositorySlug,
                                                            Long pullRequestId,
                                                            Long since,
                                                            Long until) {
        List<ProgressRegistry.ProgressSnapshot> snapshots = progressRegistry.listActive(
                projectKey,
                repositorySlug,
                pullRequestId);
        if (snapshots.isEmpty()) {
            return Collections.emptyList();
        }
        return snapshots.stream()
                .filter(snapshot -> matchesWindow(snapshot, since, until))
                .map(this::toOngoingEntry)
                .collect(Collectors.toList());
    }

    private boolean matchesWindow(ProgressRegistry.ProgressSnapshot snapshot, Long since, Long until) {
        long lastUpdated = snapshot.getLastUpdatedAt();
        long started = snapshot.getStartedAt();
        if (since != null && lastUpdated > 0 && lastUpdated < since) {
            return false;
        }
        if (until != null && started > 0 && started > until) {
            return false;
        }
        return true;
    }

    private Map<String, Object> toOngoingEntry(ProgressRegistry.ProgressSnapshot snapshot) {
        ProgressRegistry.ProgressMetadata meta = snapshot.getMetadata();
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", null);
        map.put("ongoing", true);
        map.put("runId", meta.getRunId());
        map.put("projectKey", meta.getProjectKey());
        map.put("repositorySlug", meta.getRepositorySlug());
        map.put("pullRequestId", meta.getPullRequestId());
        map.put("reviewStatus", "ONGOING");
        map.put("reviewOutcome", "RUNNING");
        map.put("reviewStartTime", snapshot.getStartedAt());
        map.put("reviewEndTime", null);
        map.put("durationSeconds", null);
        map.put("totalIssuesFound", 0);
        map.put("criticalIssues", 0);
        map.put("highIssues", 0);
        map.put("mediumIssues", 0);
        map.put("lowIssues", 0);
        map.put("hasBlockingIssues", false);
        map.put("updateReview", meta.isUpdate());
        map.put("manual", meta.isManual());

        ProgressEvent latest = getLatestEvent(snapshot);
        String stageDetail = buildStageDetail(latest);
        Integer percent = latest != null ? latest.getPercentComplete() : null;
        map.put("statusDetail", stageDetail);
        map.put("percentComplete", percent);
        map.put("summary", buildOngoingSummary(snapshot, stageDetail, percent));
        map.put("eventsRecorded", snapshot.getEventCount());
        map.put("lastUpdatedAt", snapshot.getLastUpdatedAt());
        map.put("modelUsed", "—");
        return map;
    }

    private ProgressEvent getLatestEvent(ProgressRegistry.ProgressSnapshot snapshot) {
        List<ProgressEvent> events = snapshot.getEvents();
        if (events == null || events.isEmpty()) {
            return null;
        }
        return events.get(events.size() - 1);
    }

    private String buildStageDetail(ProgressEvent event) {
        if (event == null) {
            return "Awaiting first milestone";
        }
        String stage = humanizeStage(event.getStage());
        Map<String, Object> details = event.getDetails();
        if (details != null) {
            Object analyzing = details.get("currentlyAnalyzing");
            if (analyzing == null || Objects.toString(analyzing, "").isBlank()) {
                analyzing = details.get("chunkPlanSummary");
            }
            if (analyzing != null) {
                stage = Objects.toString(analyzing, stage);
            }
            Object circuitState = details.get("circuitState");
            if (circuitState != null) {
                String stateText = Objects.toString(circuitState, "").trim();
                if (!stateText.isEmpty() && !"CLOSED".equalsIgnoreCase(stateText)) {
                    stage = stage + " · Circuit " + stateText;
                }
            }
        }
        Integer percent = event.getPercentComplete();
        if (percent != null && percent > 0) {
            return stage + " (" + percent + "%)";
        }
        return stage;
    }

    private String buildOngoingSummary(ProgressRegistry.ProgressSnapshot snapshot,
                                       String stageDetail,
                                       Integer percent) {
        List<String> parts = new ArrayList<>();
        parts.add("Running");
        if (stageDetail != null && !stageDetail.isBlank()) {
            parts.add(stageDetail);
        } else if (percent != null) {
            parts.add(percent + "% complete");
        }
        long updated = snapshot.getLastUpdatedAt();
        if (updated > 0) {
            parts.add("Updated " + formatTimestamp(updated));
        }
        if (snapshot.getEventCount() > 0) {
            parts.add(snapshot.getEventCount() == 1
                    ? "1 milestone"
                    : snapshot.getEventCount() + " milestones");
        }
        return String.join(" · ", parts);
    }

    private String humanizeStage(String value) {
        if (value == null || value.isEmpty()) {
            return "Queued";
        }
        String normalized = value.replace('.', ' ').replace('_', ' ').replace('-', ' ').trim();
        if (normalized.isEmpty()) {
            return "Queued";
        }
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
        return builder.toString();
    }

    private String formatTimestamp(long epochMillis) {
        try {
            return DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(
                    Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()));
        } catch (Exception ex) {
            return Long.toString(epochMillis);
        }
    }

    private Map<String, Object> queueStatsToMap() {
        if (concurrencyController == null) {
            return Collections.emptyMap();
        }
        ReviewConcurrencyController.QueueStats stats = concurrencyController.snapshot();
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("maxConcurrent", stats.getMaxConcurrent());
        map.put("maxQueued", stats.getMaxQueued());
        map.put("activeReviews", stats.getActive());
        map.put("waitingReviews", stats.getWaiting());
        map.put("availableSlots", Math.max(0, stats.getMaxConcurrent() - stats.getActive()));
        map.put("capturedAt", stats.getCapturedAt());
        return map;
    }

    private Map<String, String> error(String message) {
        Map<String, String> map = new HashMap<>();
        map.put("error", message);
        return map;
    }

    @GET
    @Path("/{id}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getHistoryEntry(@Context HttpServletRequest request,
                                    @PathParam("id") long historyId) {
        UserProfile profile = userManager.getRemoteUser(request);
        if (!isSystemAdmin(profile)) {
            return Response.status(Response.Status.FORBIDDEN)
                    .entity(error("Access denied. Administrator privileges required."))
                    .build();
        }

        Optional<Map<String, Object>> entry = historyService.getHistoryById(historyId);
        if (entry.isEmpty()) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(error("History entry not found"))
                    .build();
        }
        return Response.ok(entry.get()).build();
    }

    @GET
    @Path("/{id}/chunks")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getHistoryChunks(@Context HttpServletRequest request,
                                     @PathParam("id") long historyId,
                                     @QueryParam("limit") Integer limitParam) {
        UserProfile profile = userManager.getRemoteUser(request);
        if (!isSystemAdmin(profile)) {
            return Response.status(Response.Status.FORBIDDEN)
                    .entity(error("Access denied. Administrator privileges required."))
                    .build();
        }

        int limit = (limitParam == null) ? 100 : limitParam;
        Map<String, Object> chunks = historyService.getChunks(historyId, limit);
        return Response.ok(chunks).build();
    }
}
