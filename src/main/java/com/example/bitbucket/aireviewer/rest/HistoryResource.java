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
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
                                @QueryParam("limit") Integer limitParam) {
        UserProfile profile = userManager.getRemoteUser(request);
        if (!isSystemAdmin(profile)) {
            return Response.status(Response.Status.FORBIDDEN)
                    .entity(error("Access denied. Administrator privileges required."))
                    .build();
        }

        int limit = (limitParam == null) ? 20 : limitParam;
        try {
            List<Map<String, Object>> entries = historyService.getHistory(
                    projectKey,
                    repositorySlug,
                    pullRequestId,
                    limit);
            Map<String, Object> payload = new HashMap<>();
            payload.put("entries", entries);
            payload.put("count", entries.size());
            return Response.ok(payload).build();
        } catch (Exception ex) {
            log.error("Failed to fetch AI review history", ex);
            return Response.serverError()
                    .entity(error("Failed to fetch review history: " + ex.getMessage()))
                    .build();
        }
    }

    private boolean isSystemAdmin(UserProfile profile) {
        return profile != null && userManager.isSystemAdmin(profile.getUserKey());
    }

    private Map<String, String> error(String message) {
        Map<String, String> map = new HashMap<>();
        map.put("error", message);
        return map;
    }
}

