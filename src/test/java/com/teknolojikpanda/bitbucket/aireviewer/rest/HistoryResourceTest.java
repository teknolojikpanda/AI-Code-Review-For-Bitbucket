package com.teknolojikpanda.bitbucket.aireviewer.rest;

import com.atlassian.sal.api.user.UserManager;
import com.atlassian.sal.api.user.UserProfile;
import com.teknolojikpanda.bitbucket.aireviewer.service.ReviewHistoryService;
import org.junit.Before;
import org.junit.Test;

import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.core.Response;
import java.util.Collections;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

public class HistoryResourceTest {

    private UserManager userManager;
    private ReviewHistoryService historyService;
    private HttpServletRequest request;
    private UserProfile profile;
    private HistoryResource resource;

    @Before
    public void setUp() {
        System.setProperty("javax.ws.rs.ext.RuntimeDelegate", "com.sun.jersey.server.impl.provider.RuntimeDelegateImpl");
        userManager = mock(UserManager.class);
        historyService = mock(ReviewHistoryService.class);
        request = mock(HttpServletRequest.class);
        profile = mock(UserProfile.class);
        resource = new HistoryResource(userManager, historyService);
    }

    @Test
    public void backfillChunkTelemetryDeniedForNonAdmin() {
        when(userManager.getRemoteUser(request)).thenReturn(profile);
        when(userManager.isSystemAdmin(profile.getUserKey())).thenReturn(false);

        Response response = resource.backfillChunkTelemetry(request, 100);

        assertEquals(Response.Status.FORBIDDEN.getStatusCode(), response.getStatus());
        @SuppressWarnings("unchecked")
        Map<String, String> payload = (Map<String, String>) response.getEntity();
        assertEquals("Access denied. Administrator privileges required.", payload.get("error"));
        verify(historyService, never()).backfillChunkTelemetry(anyInt());
    }

    @Test
    public void backfillChunkTelemetryDefaultsLimitForAdmin() {
        when(userManager.getRemoteUser(request)).thenReturn(profile);
        when(userManager.isSystemAdmin(profile.getUserKey())).thenReturn(true);
        Map<String, Object> result = Collections.singletonMap("chunksCreated", 3);
        when(historyService.backfillChunkTelemetry(eq(0))).thenReturn(result);

        Response response = resource.backfillChunkTelemetry(request, null);

        assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) response.getEntity();
        assertSame(result, payload);
        verify(historyService).backfillChunkTelemetry(0);
    }

    @Test
    public void backfillChunkTelemetryPassesThroughProvidedLimit() {
        when(userManager.getRemoteUser(request)).thenReturn(profile);
        when(userManager.isSystemAdmin(profile.getUserKey())).thenReturn(true);
        Map<String, Object> result = Collections.singletonMap("historiesUpdated", 2);
        when(historyService.backfillChunkTelemetry(eq(25))).thenReturn(result);

        Response response = resource.backfillChunkTelemetry(request, 25);

        assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) response.getEntity();
        assertSame(result, payload);
        verify(historyService).backfillChunkTelemetry(25);
    }

    @Test
    public void metricsSummaryDeniedForNonAdmin() {
        when(userManager.getRemoteUser(request)).thenReturn(profile);
        when(userManager.isSystemAdmin(profile.getUserKey())).thenReturn(false);

        Response response = resource.getMetrics(request, "WOR", "repo", 5L, null, null);

        assertEquals(Response.Status.FORBIDDEN.getStatusCode(), response.getStatus());
        @SuppressWarnings("unchecked")
        Map<String, String> payload = (Map<String, String>) response.getEntity();
        assertEquals("Access denied. Administrator privileges required.", payload.get("error"));
        verify(historyService, never()).getMetricsSummary(any(), any(), any(), any(), any());
    }

    @Test
    public void metricsSummaryReturnsAggregatedPayloadForAdmin() {
        when(userManager.getRemoteUser(request)).thenReturn(profile);
        when(userManager.isSystemAdmin(profile.getUserKey())).thenReturn(true);
        Map<String, Object> summary = Collections.singletonMap(
                "ioTotals",
                Collections.singletonMap("chunkCount", 4L));
        when(historyService.getMetricsSummary(eq("WOR"), eq("repo"), eq(7L), isNull(), isNull()))
                .thenReturn(summary);

        Response response = resource.getMetrics(request, "WOR", "repo", 7L, -5L, 0L);

        assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) response.getEntity();
        assertSame(summary, payload);
        verify(historyService).getMetricsSummary(eq("WOR"), eq("repo"), eq(7L), isNull(), isNull());
    }
}
