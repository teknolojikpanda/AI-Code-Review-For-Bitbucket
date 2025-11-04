package com.example.bitbucket.aireviewer.rest;

import com.atlassian.bitbucket.permission.Permission;
import com.atlassian.bitbucket.permission.PermissionService;
import com.atlassian.bitbucket.project.Project;
import com.atlassian.bitbucket.repository.Repository;
import com.atlassian.bitbucket.repository.RepositoryService;
import com.atlassian.bitbucket.user.ApplicationUser;
import com.atlassian.bitbucket.user.UserService;
import com.atlassian.sal.api.user.UserKey;
import com.atlassian.sal.api.user.UserManager;
import com.atlassian.sal.api.user.UserProfile;
import com.example.bitbucket.aireviewer.ao.AIReviewHistory;
import com.example.bitbucket.aireviewer.progress.ProgressEvent;
import com.example.bitbucket.aireviewer.progress.ProgressRegistry;
import com.example.bitbucket.aireviewer.progress.ProgressRegistry.ProgressMetadata;
import com.example.bitbucket.aireviewer.service.ReviewHistoryService;
import org.junit.Before;
import org.junit.Test;

import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.core.Response;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class ProgressResourceIntegrationTest {

    private UserManager userManager;
    private UserService userService;
    private RepositoryService repositoryService;
    private PermissionService permissionService;
    private ReviewHistoryService historyService;
    private ProgressRegistry progressRegistry;
    private ProgressResource resource;

    private HttpServletRequest request;
    private UserProfile profile;
    private ApplicationUser applicationUser;
    private Repository repository;

    @Before
    public void setUp() {
        userManager = mock(UserManager.class);
        userService = mock(UserService.class);
        repositoryService = mock(RepositoryService.class);
        permissionService = mock(PermissionService.class);
        historyService = mock(ReviewHistoryService.class);
        progressRegistry = new ProgressRegistry();

        resource = new ProgressResource(
                userManager,
                userService,
                repositoryService,
                permissionService,
                progressRegistry,
                historyService);

        request = mock(HttpServletRequest.class);
        profile = mock(UserProfile.class);
        applicationUser = mock(ApplicationUser.class);
        repository = mock(Repository.class);
        Project project = mock(Project.class);

        when(userManager.getRemoteUser(request)).thenReturn(profile);
        when(userManager.isSystemAdmin(any(UserKey.class))).thenReturn(false);
        when(profile.getUsername()).thenReturn("admin");
        when(userService.getUserBySlug(eq("admin"))).thenReturn(applicationUser);

        when(repositoryService.getBySlug(anyString(), anyString())).thenReturn(repository);
        when(repository.getProject()).thenReturn(project);
        when(permissionService.hasRepositoryPermission(eq(applicationUser), eq(repository), eq(Permission.REPO_READ)))
                .thenReturn(true);
        when(permissionService.hasRepositoryPermission(eq(applicationUser), eq(repository), eq(Permission.REPO_ADMIN)))
                .thenReturn(true);
        when(permissionService.hasProjectPermission(eq(applicationUser), eq(project), eq(Permission.PROJECT_READ)))
                .thenReturn(false);
        when(permissionService.hasProjectPermission(eq(applicationUser), eq(project), eq(Permission.PROJECT_ADMIN)))
                .thenReturn(false);
    }

    @Test
    public void currentReturnsActiveSnapshot() {
        when(profile.getUserKey()).thenReturn(new UserKey("snapshot-user"));

        ProgressMetadata metadata = new ProgressMetadata("PROJ1", "repo", 42L, "run-1", false, false, false);
        progressRegistry.start(metadata);
        progressRegistry.record(metadata, ProgressEvent.builder("review.started").percentComplete(10).build());

        Response response = resource.getCurrent(request, "PROJ1", "repo", 42L);

        assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) response.getEntity();
        assertEquals("PROJ1", payload.get("projectKey"));
        assertEquals("repo", payload.get("repositorySlug"));
        assertEquals(42L, payload.get("pullRequestId"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> events = (List<Map<String, Object>>) payload.get("events");
        assertEquals(1, events.size());
        assertEquals("review.started", events.get(0).get("stage"));
    }

    @Test
    public void currentRateLimitsAfterThreshold() {
        when(profile.getUserKey()).thenReturn(new UserKey("rate-limit-user"));

        Response last = null;
        for (int i = 0; i < 30; i++) {
            last = resource.getCurrent(request, "PROJ-RATE", "repo", 99L);
            assertEquals(Response.Status.NOT_FOUND.getStatusCode(), last.getStatus());
        }

        Response blocked = resource.getCurrent(request, "PROJ-RATE", "repo", 99L);
        assertEquals(429, blocked.getStatus());
    }

    @Test
    public void historicalAccessDeniedWithoutPermission() {
        when(profile.getUserKey()).thenReturn(new UserKey("denied-user"));
        when(permissionService.hasRepositoryPermission(any(ApplicationUser.class), any(Repository.class), eq(Permission.REPO_ADMIN)))
                .thenReturn(false);
        when(permissionService.hasRepositoryPermission(any(ApplicationUser.class), any(Repository.class), eq(Permission.REPO_READ)))
                .thenReturn(false);
        when(permissionService.hasProjectPermission(any(ApplicationUser.class), any(Project.class), eq(Permission.PROJECT_ADMIN)))
                .thenReturn(false);
        when(permissionService.hasProjectPermission(any(ApplicationUser.class), any(Project.class), eq(Permission.PROJECT_READ)))
                .thenReturn(false);

        AIReviewHistory history = mock(AIReviewHistory.class);
        when(history.getProjectKey()).thenReturn("PROJ-DENIED");
        when(history.getRepositorySlug()).thenReturn("repo");
        when(historyService.findEntityById(anyLong())).thenReturn(Optional.of(history));

        Response response = resource.getHistorical(request, 500L);

        assertEquals(Response.Status.FORBIDDEN.getStatusCode(), response.getStatus());
    }

    @Test
    public void historicalReturnsPersistedProgress() {
        when(profile.getUserKey()).thenReturn(new UserKey("history-user"));

        AIReviewHistory history = mock(AIReviewHistory.class);
        when(history.getProjectKey()).thenReturn("PROJ-HIST");
        when(history.getRepositorySlug()).thenReturn("repo");
        when(history.getPullRequestId()).thenReturn(7L);
        when(historyService.findEntityById(777L)).thenReturn(Optional.of(history));
        when(historyService.getHistoryById(777L)).thenReturn(Optional.of(Collections.singletonMap(
                "progress",
                Collections.singletonList(Collections.singletonMap("stage", "review.completed"))
        )));

        Response response = resource.getHistorical(request, 777L);

        assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) response.getEntity();
        assertEquals(777L, payload.get("historyId"));
        assertEquals("PROJ-HIST", payload.get("projectKey"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> progress = (List<Map<String, Object>>) payload.get("progress");
        assertTrue(progress.stream().anyMatch(item -> "review.completed".equals(item.get("stage"))));
    }
}
