package com.teknolojikpanda.bitbucket.aireviewer.rest;

import com.atlassian.sal.api.user.UserKey;
import com.atlassian.sal.api.user.UserManager;
import com.atlassian.sal.api.user.UserProfile;
import com.teknolojikpanda.bitbucket.aireviewer.service.GuardrailsTelemetryService;
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

public class MetricsResourceTest {

    private UserManager userManager;
    private GuardrailsTelemetryService telemetryService;
    private MetricsResource resource;
    private HttpServletRequest request;
    private UserProfile profile;

    @Before
    public void setUp() {
        userManager = mock(UserManager.class);
        telemetryService = mock(GuardrailsTelemetryService.class);
        resource = new MetricsResource(userManager, telemetryService);
        request = mock(HttpServletRequest.class);
        profile = mock(UserProfile.class);
    }

    @Test
    public void exportRequiresAuthentication() {
        when(userManager.getRemoteUser(request)).thenReturn(null);

        Response response = resource.exportMetrics(request);

        assertEquals(Response.Status.UNAUTHORIZED.getStatusCode(), response.getStatus());
        verifyNoInteractions(telemetryService);
    }

    @Test
    public void exportRequiresAdmin() {
        when(userManager.getRemoteUser(request)).thenReturn(profile);
        UserKey key = new UserKey("user");
        when(profile.getUserKey()).thenReturn(key);
        when(userManager.isSystemAdmin(key)).thenReturn(false);

        Response response = resource.exportMetrics(request);

        assertEquals(Response.Status.FORBIDDEN.getStatusCode(), response.getStatus());
        verifyNoInteractions(telemetryService);
    }

    @Test
    public void exportReturnsMetrics() {
        when(userManager.getRemoteUser(request)).thenReturn(profile);
        UserKey key = new UserKey("admin");
        when(profile.getUserKey()).thenReturn(key);
        when(userManager.isSystemAdmin(key)).thenReturn(true);
        Map<String, Object> export = Collections.singletonMap("metrics", Collections.emptyList());
        when(telemetryService.exportMetrics()).thenReturn(export);

        Response response = resource.exportMetrics(request);

        assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) response.getEntity();
        assertEquals("admin", payload.get("requestedBy"));
        assertSame(export.get("metrics"), payload.get("metrics"));
    }
}
