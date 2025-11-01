package com.example.bitbucket.aireviewer.rest;

import com.atlassian.plugin.spring.scanner.annotation.imports.ComponentImport;
import com.atlassian.sal.api.user.UserManager;
import com.atlassian.sal.api.user.UserProfile;
import com.example.bitbucket.aireviewer.service.ReviewHistoryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.inject.Named;
import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.PathParam;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * REST resource exposing AI review history for administrators.
 */
@Path("/history")
@Named
public class HistoryResource {

    private static final Logger log = LoggerFactory.getLogger(HistoryResource.class);

    private final UserManager userManager;
    private final ReviewHistoryService historyService;

    @Inject
    public HistoryResource(@ComponentImport UserManager userManager,
                           ReviewHistoryService historyService) {
        this.userManager = userManager;
        this.historyService = historyService;
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
            Map<String, Object> payload = new HashMap<>();
            payload.put("entries", page.getValues());
            payload.put("count", page.getValues().size());
            payload.put("total", page.getTotal());
            payload.put("limit", page.getLimit());
            payload.put("offset", page.getOffset());
            payload.put("nextOffset", computeNextOffset(page));
            payload.put("prevOffset", computePrevOffset(page));
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
