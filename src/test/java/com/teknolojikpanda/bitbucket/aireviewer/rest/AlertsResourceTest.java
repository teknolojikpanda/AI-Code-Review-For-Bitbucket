package com.teknolojikpanda.bitbucket.aireviewer.rest;

import com.atlassian.sal.api.user.UserKey;
import com.atlassian.sal.api.user.UserManager;
import com.atlassian.sal.api.user.UserProfile;
import com.teknolojikpanda.bitbucket.aireviewer.service.GuardrailsAlertingService;
import org.junit.Before;
import org.junit.Test;

import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.core.Response;
import java.util.Collections;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

public class AlertsResourceTest {

    private UserManager userManager;
    private GuardrailsAlertingService alertingService;
    private AlertsResource resource;
    private HttpServletRequest request;
    private UserProfile profile;

    @Before
    public void setUp() {
        userManager = mock(UserManager.class);
        alertingService = mock(GuardrailsAlertingService.class);
        resource = new AlertsResource(userManager, alertingService);
        request = mock(HttpServletRequest.class);
        profile = mock(UserProfile.class);
    }

    @Test
    public void requiresAuthentication() {
        when(userManager.getRemoteUser(request)).thenReturn(null);

        Response response = resource.getAlerts(request);

        assertEquals(Response.Status.UNAUTHORIZED.getStatusCode(), response.getStatus());
        verifyNoInteractions(alertingService);
    }

    @Test
    public void requiresAdmin() {
        when(userManager.getRemoteUser(request)).thenReturn(profile);
        UserKey key = new UserKey("user");
        when(profile.getUserKey()).thenReturn(key);
        when(userManager.isSystemAdmin(key)).thenReturn(false);

        Response response = resource.getAlerts(request);

        assertEquals(Response.Status.FORBIDDEN.getStatusCode(), response.getStatus());
        verifyNoInteractions(alertingService);
    }

    @Test
    public void returnsAlerts() {
        when(userManager.getRemoteUser(request)).thenReturn(profile);
        UserKey key = new UserKey("admin");
        when(profile.getUserKey()).thenReturn(key);
        when(userManager.isSystemAdmin(key)).thenReturn(true);
        GuardrailsAlertingService.AlertSnapshot snapshot = mock(GuardrailsAlertingService.AlertSnapshot.class);
        when(snapshot.getGeneratedAt()).thenReturn(123L);
        when(snapshot.getAlerts()).thenReturn(Collections.emptyList());
        when(snapshot.getRuntime()).thenReturn(Collections.emptyMap());
        when(alertingService.evaluateAndNotify()).thenReturn(snapshot);

        Response response = resource.getAlerts(request);

        assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) response.getEntity();
        assertEquals(123L, payload.get("generatedAt"));
        assertSame(snapshot.getAlerts(), payload.get("alerts"));
    }
}
