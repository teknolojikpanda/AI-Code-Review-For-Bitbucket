package com.teknolojikpanda.bitbucket.aireviewer.rest;

import com.atlassian.bitbucket.pull.PullRequestService;
import com.atlassian.bitbucket.pull.PullRequest;
import com.atlassian.bitbucket.repository.Repository;
import com.atlassian.bitbucket.repository.RepositoryService;
import com.atlassian.sal.api.user.UserKey;
import com.atlassian.sal.api.user.UserManager;
import com.atlassian.sal.api.user.UserProfile;
import com.teknolojikpanda.bitbucket.aireviewer.service.AIReviewService;
import org.junit.Before;
import org.junit.Test;

import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.core.Response;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class ManualReviewResourceSecurityTest {

    private UserManager userManager;
    private RepositoryService repositoryService;
    private PullRequestService pullRequestService;
    private AIReviewService aiReviewService;
    private HttpServletRequest request;
    private UserProfile profile;
    private ManualReviewResource resource;

    @Before
    public void setUp() {
        System.setProperty("javax.ws.rs.ext.RuntimeDelegate", "com.sun.jersey.server.impl.provider.RuntimeDelegateImpl");

        userManager = mock(UserManager.class);
        repositoryService = mock(RepositoryService.class);
        pullRequestService = mock(PullRequestService.class);
        aiReviewService = mock(AIReviewService.class);
        request = mock(HttpServletRequest.class);
        profile = mock(UserProfile.class);

        UserKey adminKey = new UserKey("admin");
        when(profile.getUserKey()).thenReturn(adminKey);
        when(profile.getUsername()).thenReturn("admin");
        when(userManager.getRemoteUser(request)).thenReturn(profile);
        when(userManager.isSystemAdmin(adminKey)).thenReturn(true);

        resource = new ManualReviewResource(userManager, repositoryService, pullRequestService, aiReviewService);
    }

    @Test
    public void manualReviewFailureReturnsGenericErrorWithCorrelationId() {
        ManualReviewResource.ManualReviewRequest payload = new ManualReviewResource.ManualReviewRequest();
        payload.setProjectKey("PROJ");
        payload.setRepositorySlug("repo");
        payload.setPullRequestId(12L);

        Repository repository = mock(Repository.class);
        PullRequest pullRequest = mock(PullRequest.class);
        when(repository.getId()).thenReturn(99);
        when(repositoryService.getBySlug("PROJ", "repo")).thenReturn(repository);
        when(pullRequestService.getById(99, 12L)).thenReturn(pullRequest);
        when(aiReviewService.manualReview(pullRequest, false, false))
            .thenThrow(new RuntimeException("sql://internal-db token=abc"));

        Response response = resource.triggerReview(request, payload);

        assertEquals(Response.Status.INTERNAL_SERVER_ERROR.getStatusCode(), response.getStatus());
        @SuppressWarnings("unchecked")
        Map<String, Object> entity = (Map<String, Object>) response.getEntity();

        assertEquals("Manual review failed due to internal error.", entity.get("error"));
        assertNotNull(entity.get("correlationId"));
        String correlation = String.valueOf(entity.get("correlationId"));
        assertTrue(correlation.length() >= 8);
        assertFalse(String.valueOf(entity.get("error")).contains("internal-db"));
    }
}
