package com.teknolojikpanda.bitbucket.aireviewer.rest;

import com.atlassian.bitbucket.user.UserService;
import com.atlassian.sal.api.user.UserKey;
import com.atlassian.sal.api.user.UserManager;
import com.atlassian.sal.api.user.UserProfile;
import com.teknolojikpanda.bitbucket.aicode.core.ReviewConfigFactory;
import com.teknolojikpanda.bitbucket.aireviewer.service.AIReviewerConfigService;
import com.teknolojikpanda.bitbucket.aireviewer.service.GuardrailsRateLimitOverrideService;
import com.teknolojikpanda.bitbucket.aireviewer.service.GuardrailsRateLimitStore;
import com.teknolojikpanda.bitbucket.aireviewer.service.ReviewRateLimiter;
import org.junit.Before;
import org.junit.Test;

import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.core.Response;
import java.util.Collections;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class ConfigResourceSecurityTest {

    private UserManager userManager;
    private UserService userService;
    private AIReviewerConfigService configService;
    private ReviewConfigFactory configFactory;
    private ReviewRateLimiter rateLimiter;
    private GuardrailsRateLimitOverrideService overrideService;
    private GuardrailsRateLimitStore rateLimitStore;
    private HttpServletRequest request;
    private UserProfile profile;
    private ConfigResource resource;

    @Before
    public void setUp() {
        System.setProperty("javax.ws.rs.ext.RuntimeDelegate", "com.sun.jersey.server.impl.provider.RuntimeDelegateImpl");

        userManager = mock(UserManager.class);
        userService = mock(UserService.class);
        configService = mock(AIReviewerConfigService.class);
        configFactory = mock(ReviewConfigFactory.class);
        rateLimiter = mock(ReviewRateLimiter.class);
        overrideService = mock(GuardrailsRateLimitOverrideService.class);
        rateLimitStore = mock(GuardrailsRateLimitStore.class);
        request = mock(HttpServletRequest.class);
        profile = mock(UserProfile.class);

        UserKey adminKey = new UserKey("admin");
        when(profile.getUserKey()).thenReturn(adminKey);
        when(profile.getUsername()).thenReturn("admin");
        when(userManager.getRemoteUser(request)).thenReturn(profile);
        when(userManager.isSystemAdmin(adminKey)).thenReturn(true);

        resource = new ConfigResource(userManager,
                userService,
                configService,
                configFactory,
                rateLimiter,
                overrideService,
                rateLimitStore);
    }

    @Test
    public void getConfigurationInternalFailureReturnsCorrelationIdWithoutLeakingMessage() {
        when(configService.getConfigurationAsMap())
                .thenThrow(new RuntimeException("jdbc://internal-host:5432/secrets"));

        Response response = resource.getConfiguration(request);

        assertEquals(Response.Status.INTERNAL_SERVER_ERROR.getStatusCode(), response.getStatus());
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) response.getEntity();
        assertNotNull(payload.get("correlationId"));
        assertTrue(String.valueOf(payload.get("correlationId")).length() >= 8);

        String error = String.valueOf(payload.get("error"));
        assertFalse(error.contains("jdbc://internal-host"));
        assertFalse(error.contains("secrets"));
        assertTrue(error.contains("Failed to get configuration"));
    }

    @Test
    public void updateConfigurationValidationFailureReturnsGenericMessageWithCorrelationId() {
        when(configService.getDefaultConfiguration()).thenReturn(Collections.emptyMap());
        when(configService.updateConfiguration(Collections.emptyMap()))
                .thenThrow(new IllegalArgumentException("ssh://internal token invalid"));

        Response response = resource.updateConfiguration(Collections.emptyMap(), request);

        assertEquals(Response.Status.BAD_REQUEST.getStatusCode(), response.getStatus());
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) response.getEntity();
        assertNotNull(payload.get("correlationId"));
        assertEquals("Invalid configuration payload", payload.get("error"));
    }

    @Test
    public void testConnectionRejectsNullPayload() {
        Response response = resource.testConnection(null, request);

        assertEquals(Response.Status.BAD_REQUEST.getStatusCode(), response.getStatus());
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) response.getEntity();
        assertEquals("Request payload is required", payload.get("error"));
    }

    @Test
    public void testConnectionRejectsLocalhostTarget() {
        Response response = resource.testConnection(Map.of("ollamaUrl", "http://localhost:11434"), request);

        assertEquals(Response.Status.BAD_REQUEST.getStatusCode(), response.getStatus());
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) response.getEntity();
        assertEquals("Invalid URL format or forbidden host", payload.get("error"));
        verify(configService, never()).testOllamaConnection("http://localhost:11434");
    }
}
