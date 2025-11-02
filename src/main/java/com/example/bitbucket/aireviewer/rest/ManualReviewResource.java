package com.example.bitbucket.aireviewer.rest;

import com.atlassian.bitbucket.pull.PullRequest;
import com.atlassian.bitbucket.pull.PullRequestService;
import com.atlassian.bitbucket.repository.Repository;
import com.atlassian.bitbucket.repository.RepositoryService;
import com.atlassian.plugin.spring.scanner.annotation.imports.ComponentImport;
import com.atlassian.sal.api.user.UserManager;
import com.atlassian.sal.api.user.UserProfile;
import com.example.bitbucket.aireviewer.dto.ReviewResult;
import com.example.bitbucket.aireviewer.service.AIReviewService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.inject.Named;
import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.Consumes;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * REST endpoint allowing administrators to trigger manual AI reviews.
 */
@Path("/history/manual")
@Named
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class ManualReviewResource {

    private static final Logger log = LoggerFactory.getLogger(ManualReviewResource.class);

    private final UserManager userManager;
    private final RepositoryService repositoryService;
    private final PullRequestService pullRequestService;
    private final AIReviewService aiReviewService;

    @Inject
    public ManualReviewResource(@ComponentImport UserManager userManager,
                                @ComponentImport RepositoryService repositoryService,
                                @ComponentImport PullRequestService pullRequestService,
                                AIReviewService aiReviewService) {
        this.userManager = Objects.requireNonNull(userManager, "userManager");
        this.repositoryService = Objects.requireNonNull(repositoryService, "repositoryService");
        this.pullRequestService = Objects.requireNonNull(pullRequestService, "pullRequestService");
        this.aiReviewService = Objects.requireNonNull(aiReviewService, "aiReviewService");
    }

    @POST
    public Response triggerReview(@Context HttpServletRequest request, ManualReviewRequest payload) {
        if (payload == null) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(error("Request body is required."))
                    .build();
        }

        UserProfile profile = userManager.getRemoteUser(request);
        if (!isSystemAdmin(profile)) {
            return Response.status(Response.Status.FORBIDDEN)
                    .entity(error("Access denied. Administrator privileges required."))
                    .build();
        }

        String projectKey = safeTrim(payload.getProjectKey());
        String repositorySlug = safeTrim(payload.getRepositorySlug());
        Long prId = payload.getPullRequestId();

        if (projectKey == null || projectKey.isEmpty()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(error("projectKey is required."))
                    .build();
        }
        if (repositorySlug == null || repositorySlug.isEmpty()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(error("repositorySlug is required."))
                    .build();
        }
        if (prId == null || prId <= 0) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(error("pullRequestId must be a positive number."))
                    .build();
        }

        Repository repository = repositoryService.getBySlug(projectKey, repositorySlug);
        if (repository == null) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(error(String.format("Repository %s/%s not found.", projectKey, repositorySlug)))
                    .build();
        }

        PullRequest pullRequest = pullRequestService.getById(repository.getId(), prId);
        if (pullRequest == null) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(error(String.format("Pull request #%d not found in %s/%s.", prId, projectKey, repositorySlug)))
                    .build();
        }

        boolean force = Boolean.TRUE.equals(payload.getForce());
        boolean treatAsUpdate = Boolean.TRUE.equals(payload.getTreatAsUpdate());

        log.info("Manual review requested by {} for PR #{} in {}/{} (force={}, treatAsUpdate={})",
                profile != null ? profile.getUsername() : "unknown",
                prId,
                projectKey,
                repositorySlug,
                force,
                treatAsUpdate);

        try {
            ReviewResult result = aiReviewService.manualReview(pullRequest, force, treatAsUpdate);
            Map<String, Object> response = toResultPayload(result, force, treatAsUpdate);
            return Response.ok(response).build();
        } catch (Exception ex) {
            log.error("Manual review failed for PR #{} in {}/{}: {}", prId, projectKey, repositorySlug, ex.getMessage(), ex);
            return Response.serverError()
                    .entity(error("Manual review failed: " + ex.getMessage()))
                    .build();
        }
    }

    private boolean isSystemAdmin(UserProfile profile) {
        return profile != null && userManager.isSystemAdmin(profile.getUserKey());
    }

    private Map<String, Object> error(String message) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("error", message);
        return map;
    }

    private Map<String, Object> toResultPayload(ReviewResult result, boolean force, boolean treatAsUpdate) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("pullRequestId", result.getPullRequestId());
        payload.put("status", result.getStatus().getValue());
        payload.put("message", result.getMessage());
        payload.put("issueCount", result.getIssueCount());
        payload.put("filesReviewed", result.getFilesReviewed());
        payload.put("filesSkipped", result.getFilesSkipped());
        payload.put("manual", true);
        payload.put("forced", force);
        payload.put("treatedAsUpdate", treatAsUpdate);
        payload.put("issues", result.getIssues());
        payload.put("metrics", result.getMetrics());
        return payload;
    }

    private String safeTrim(String value) {
        return value == null ? null : value.trim();
    }

    /**
     * Request payload for manual review trigger.
     */
    public static final class ManualReviewRequest {
        private String projectKey;
        private String repositorySlug;
        private Long pullRequestId;
        private Boolean force;
        private Boolean treatAsUpdate;

        public ManualReviewRequest() {
        }

        public String getProjectKey() {
            return projectKey;
        }

        public void setProjectKey(String projectKey) {
            this.projectKey = projectKey;
        }

        public String getRepositorySlug() {
            return repositorySlug;
        }

        public void setRepositorySlug(String repositorySlug) {
            this.repositorySlug = repositorySlug;
        }

        public Long getPullRequestId() {
            return pullRequestId;
        }

        public void setPullRequestId(Long pullRequestId) {
            this.pullRequestId = pullRequestId;
        }

        public Boolean getForce() {
            return force;
        }

        public void setForce(Boolean force) {
            this.force = force;
        }

        public Boolean getTreatAsUpdate() {
            return treatAsUpdate;
        }

        public void setTreatAsUpdate(Boolean treatAsUpdate) {
            this.treatAsUpdate = treatAsUpdate;
        }
    }
}
