package com.teknolojikpanda.bitbucket.aireviewer.rest;

import com.atlassian.bitbucket.permission.Permission;
import com.atlassian.bitbucket.permission.PermissionService;
import com.atlassian.bitbucket.repository.Repository;
import com.atlassian.bitbucket.repository.RepositoryService;
import com.atlassian.bitbucket.user.ApplicationUser;
import com.atlassian.bitbucket.user.UserService;
import com.atlassian.sal.api.user.UserManager;
import com.atlassian.sal.api.user.UserProfile;
import com.teknolojikpanda.bitbucket.aireviewer.ao.AIReviewHistory;
import com.teknolojikpanda.bitbucket.aireviewer.progress.ProgressEvent;
import com.teknolojikpanda.bitbucket.aireviewer.progress.ProgressRegistry;
import com.teknolojikpanda.bitbucket.aireviewer.service.Page;
import com.teknolojikpanda.bitbucket.aireviewer.service.ReviewHistoryService;
import com.teknolojikpanda.bitbucket.aireviewer.service.ReviewSchedulerStateService;
import com.teknolojikpanda.bitbucket.aireviewer.service.ReviewConcurrencyController;
import org.junit.Before;
import org.junit.Test;

import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.core.Response;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.*;

public class ProgressResourceTest {

    private UserManager userManager;
    private UserService userService;
    private RepositoryService repositoryService;
    private PermissionService permissionService;
    private ProgressRegistry progressRegistry;
    private ReviewHistoryService historyService;
    private ReviewSchedulerStateService schedulerStateService;
    private ReviewConcurrencyController concurrencyController;
    private ProgressResource resource;
    private HttpServletRequest request;
    private UserProfile profile;
    private ApplicationUser applicationUser;
    private Repository repository;

    @Before
    public void setUp() {
        System.setProperty("javax.ws.rs.ext.RuntimeDelegate", "com.sun.jersey.server.impl.provider.RuntimeDelegateImpl");
        userManager = mock(UserManager.class);
        userService = mock(UserService.class);
        repositoryService = mock(RepositoryService.class);
        permissionService = mock(PermissionService.class);
        progressRegistry = mock(ProgressRegistry.class);
        historyService = mock(ReviewHistoryService.class);
        schedulerStateService = mock(ReviewSchedulerStateService.class);
        concurrencyController = mock(ReviewConcurrencyController.class);
        request = mock(HttpServletRequest.class);
        profile = mock(UserProfile.class);
        applicationUser = mock(ApplicationUser.class);
        repository = mock(Repository.class);
        ReviewSchedulerStateService.SchedulerState schedulerState =
                new ReviewSchedulerStateService.SchedulerState(
                        ReviewSchedulerStateService.SchedulerState.Mode.ACTIVE,
                        "system",
                        "System",
                        null,
                        System.currentTimeMillis());
        when(schedulerStateService.getState()).thenReturn(schedulerState);
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
                        Collections.emptyList(),
                        Collections.emptyList());
        when(concurrencyController.snapshot()).thenReturn(stats);
        when(concurrencyController.getQueuedRequests()).thenReturn(Collections.emptyList());
        when(historyService.getRecentDurationStats(anyInt())).thenReturn(ReviewHistoryService.DurationStats.empty());
        resource = new ProgressResource(userManager, userService, repositoryService, permissionService, progressRegistry, historyService, schedulerStateService, concurrencyController);
    }

    @Test
    public void getCurrentRequiresAuthentication() {
        when(userManager.getRemoteUser(request)).thenReturn(null);

        Response response = resource.getCurrent(request, "PROJ", "repo", 1L);

        assertEquals(Response.Status.UNAUTHORIZED.getStatusCode(), response.getStatus());
    }

    @Test
    public void getCurrentReturnsNotFoundWhenNoProgress() {
        when(userManager.getRemoteUser(request)).thenReturn(profile);
        when(userService.getUserBySlug(profile.getUsername())).thenReturn(applicationUser);
        when(repositoryService.getBySlug("PROJ", "repo")).thenReturn(repository);
        when(permissionService.hasRepositoryPermission(applicationUser, repository, Permission.REPO_READ)).thenReturn(true);
        when(permissionService.hasRepositoryPermission(applicationUser, repository, Permission.REPO_ADMIN)).thenReturn(true);
        when(progressRegistry.getActive("PROJ", "repo", 1L)).thenReturn(Optional.empty());

        Response response = resource.getCurrent(request, "PROJ", "repo", 1L);

        assertEquals(Response.Status.NOT_FOUND.getStatusCode(), response.getStatus());
    }

    @Test
    public void getCurrentReturnsSnapshot() {
        when(userManager.getRemoteUser(request)).thenReturn(profile);
        when(userService.getUserBySlug(profile.getUsername())).thenReturn(applicationUser);
        when(repositoryService.getBySlug("PROJ", "repo")).thenReturn(repository);
        when(permissionService.hasRepositoryPermission(applicationUser, repository, Permission.REPO_READ)).thenReturn(true);
        when(permissionService.hasRepositoryPermission(applicationUser, repository, Permission.REPO_ADMIN)).thenReturn(true);

        ProgressRegistry.ProgressMetadata metadata = new ProgressRegistry.ProgressMetadata("PROJ", "repo", 1L, "run-1", false, false, false);
        ProgressEvent event = ProgressEvent.builder("review.started").percentComplete(0).build();
        ProgressRegistry.ProgressSnapshot snapshot = ProgressRegistry.createSnapshot(metadata, List.of(event), false, null, 100L, 150L);
        when(progressRegistry.getActive("PROJ", "repo", 1L)).thenReturn(Optional.of(snapshot));

        Response response = resource.getCurrent(request, "PROJ", "repo", 1L);

        assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) response.getEntity();
        assertEquals("PROJ", payload.get("projectKey"));
        assertEquals("repo", payload.get("repositorySlug"));
        assertEquals(1L, payload.get("pullRequestId"));
        assertEquals("in_progress", payload.get("state"));
        assertTrue(payload.containsKey("events"));
        assertEquals(1, payload.get("eventCount"));
        assertTrue(payload.get("summary").toString().contains("Running"));
        assertTrue(payload.get("completedAt") == null);
    }

    @Test
    public void getHistoricalRequiresPermission() {
        when(userManager.getRemoteUser(request)).thenReturn(profile);
        when(userService.getUserBySlug(profile.getUsername())).thenReturn(applicationUser);
        // repository missing triggers 404 before permission
        when(repositoryService.getBySlug("PROJ", "repo")).thenReturn(null);
        AIReviewHistory history = mock(AIReviewHistory.class);
        when(history.getProjectKey()).thenReturn("PROJ");
        when(history.getRepositorySlug()).thenReturn("repo");
        when(history.getPullRequestId()).thenReturn(1L);
        when(historyService.findEntityById(123L)).thenReturn(Optional.of(history));

        Response response = resource.getHistorical(request, 123L);

        assertEquals(Response.Status.NOT_FOUND.getStatusCode(), response.getStatus());
    }

    @Test
    public void getHistoricalReturnsProgress() {
        when(userManager.getRemoteUser(request)).thenReturn(profile);
        when(userService.getUserBySlug(profile.getUsername())).thenReturn(applicationUser);
        when(repositoryService.getBySlug("PROJ", "repo")).thenReturn(repository);
        when(permissionService.hasRepositoryPermission(applicationUser, repository, Permission.REPO_READ)).thenReturn(true);
        when(permissionService.hasRepositoryPermission(applicationUser, repository, Permission.REPO_ADMIN)).thenReturn(true);
        AIReviewHistory history = mock(AIReviewHistory.class);
        when(history.getProjectKey()).thenReturn("PROJ");
        when(history.getRepositorySlug()).thenReturn("repo");
        when(history.getPullRequestId()).thenReturn(1L);
        when(historyService.findEntityById(456L)).thenReturn(Optional.of(history));
        when(historyService.getHistoryById(456L)).thenReturn(Optional.of(Collections.singletonMap("progress", List.of(Collections.singletonMap("stage", "review.completed")))));

        Response response = resource.getHistorical(request, 456L);

        assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) response.getEntity();
        assertEquals(456L, payload.get("historyId"));
        assertTrue(payload.containsKey("progress"));
    }

    @Test
    public void adminQueueRequiresPermission() {
        when(userManager.getRemoteUser(request)).thenReturn(profile);
        when(userManager.isSystemAdmin(profile.getUserKey())).thenReturn(false);

        Response response = resource.getQueueSnapshot(request);

        assertEquals(Response.Status.FORBIDDEN.getStatusCode(), response.getStatus());
    }

    @Test
    public void adminQueueReturnsEntries() {
        when(userManager.getRemoteUser(request)).thenReturn(profile);
        when(userManager.isSystemAdmin(profile.getUserKey())).thenReturn(true);

        ReviewConcurrencyController.QueueStats.QueueEntry entry =
                new ReviewConcurrencyController.QueueStats.QueueEntry(
                        "run-123",
                        "PRJ",
                        "repo",
                        42L,
                        true,
                        false,
                        false,
                        1000L,
                        2,
                        3,
                        "author");
        when(concurrencyController.getQueuedRequests()).thenReturn(Collections.singletonList(entry));

        Response response = resource.getQueueSnapshot(request);

        assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) response.getEntity();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> entries = (List<Map<String, Object>>) payload.get("entries");
        assertEquals(1, entries.size());
        assertEquals("run-123", entries.get(0).get("runId"));
    }

    @Test
    public void adminQueueCancelRequiresRunId() {
        when(userManager.getRemoteUser(request)).thenReturn(profile);
        when(userManager.isSystemAdmin(profile.getUserKey())).thenReturn(true);

        Response response = resource.cancelQueuedRun(request, new ProgressResource.QueueCancelRequest());

        assertEquals(Response.Status.BAD_REQUEST.getStatusCode(), response.getStatus());
    }

    @Test
    public void adminQueueCancelReturnsNotFound() {
        when(userManager.getRemoteUser(request)).thenReturn(profile);
        when(userManager.isSystemAdmin(profile.getUserKey())).thenReturn(true);
        when(concurrencyController.cancelQueuedRun(eq("run-1"), any(), any())).thenReturn(false);

        ProgressResource.QueueCancelRequest body = new ProgressResource.QueueCancelRequest();
        body.runId = "run-1";
        Response response = resource.cancelQueuedRun(request, body);

        assertEquals(Response.Status.NOT_FOUND.getStatusCode(), response.getStatus());
    }

    @Test
    public void adminQueueCancelSucceeds() {
        when(userManager.getRemoteUser(request)).thenReturn(profile);
        when(userManager.isSystemAdmin(profile.getUserKey())).thenReturn(true);
        when(concurrencyController.cancelQueuedRun(eq("run-2"), any(), any())).thenReturn(true);

        ProgressResource.QueueCancelRequest body = new ProgressResource.QueueCancelRequest();
        body.runId = "run-2";
        Response response = resource.cancelQueuedRun(request, body);

        assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) response.getEntity();
        assertEquals(true, payload.get("canceled"));
    }

    @Test
    public void adminRunningCancelRequiresRunId() {
        when(userManager.getRemoteUser(request)).thenReturn(profile);
        when(userManager.isSystemAdmin(profile.getUserKey())).thenReturn(true);

        Response response = resource.cancelRunningRun(request, new ProgressResource.QueueCancelRequest());

        assertEquals(Response.Status.BAD_REQUEST.getStatusCode(), response.getStatus());
    }

    @Test
    public void adminRunningCancelReturnsNotFound() {
        when(userManager.getRemoteUser(request)).thenReturn(profile);
        when(userManager.isSystemAdmin(profile.getUserKey())).thenReturn(true);
        when(concurrencyController.cancelActiveRun(eq("run-active"), any(), any())).thenReturn(false);

        ProgressResource.QueueCancelRequest body = new ProgressResource.QueueCancelRequest();
        body.runId = "run-active";
        Response response = resource.cancelRunningRun(request, body);

        assertEquals(Response.Status.NOT_FOUND.getStatusCode(), response.getStatus());
    }

    @Test
    public void adminRunningCancelSucceeds() {
        when(userManager.getRemoteUser(request)).thenReturn(profile);
        when(userManager.isSystemAdmin(profile.getUserKey())).thenReturn(true);
        when(concurrencyController.cancelActiveRun(eq("run-active"), any(), any())).thenReturn(true);

        ProgressResource.QueueCancelRequest body = new ProgressResource.QueueCancelRequest();
        body.runId = "run-active";
        Response response = resource.cancelRunningRun(request, body);

        assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) response.getEntity();
        assertEquals(true, payload.get("canceled"));
    }

    @Test
    public void getRecentHistoryReturnsEntries() {
        when(userManager.getRemoteUser(request)).thenReturn(profile);
        when(userService.getUserBySlug(profile.getUsername())).thenReturn(applicationUser);
        when(repositoryService.getBySlug("PROJ", "repo")).thenReturn(repository);
        when(permissionService.hasRepositoryPermission(applicationUser, repository, Permission.REPO_READ)).thenReturn(true);
        when(permissionService.hasRepositoryPermission(applicationUser, repository, Permission.REPO_ADMIN)).thenReturn(true);

        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("id", 99L);
        entry.put("reviewStatus", "COMPLETED");
        when(historyService.getRecentSummaries("PROJ", "repo", 1L, 10, 0))
                .thenReturn(new Page<>(List.of(entry), 1, 10, 0));

        Response response = resource.getRecentHistory(request, "PROJ", "repo", 1L, null, null);

        assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) response.getEntity();
        assertEquals(1, payload.get("count"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> entries = (List<Map<String, Object>>) payload.get("entries");
        assertEquals(99L, entries.get(0).get("id"));
    }
}
