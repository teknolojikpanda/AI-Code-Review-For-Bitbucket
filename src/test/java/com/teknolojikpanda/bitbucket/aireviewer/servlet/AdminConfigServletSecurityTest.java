package com.teknolojikpanda.bitbucket.aireviewer.servlet;

import com.atlassian.bitbucket.server.ApplicationPropertiesService;
import com.atlassian.sal.api.auth.LoginUriProvider;
import com.atlassian.sal.api.user.UserKey;
import com.atlassian.sal.api.user.UserManager;
import com.atlassian.sal.api.user.UserProfile;
import com.atlassian.templaterenderer.TemplateRenderer;
import com.teknolojikpanda.bitbucket.aireviewer.service.AIReviewerConfigService;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.URI;
import java.util.Collections;
import java.util.Map;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class AdminConfigServletSecurityTest {

    private UserManager userManager;
    private LoginUriProvider loginUriProvider;
    private TemplateRenderer templateRenderer;
    private AIReviewerConfigService configService;
    private ApplicationPropertiesService applicationPropertiesService;
    private HttpServletRequest request;
    private HttpServletResponse response;

    @Before
    public void setUp() {
        userManager = mock(UserManager.class);
        loginUriProvider = mock(LoginUriProvider.class);
        templateRenderer = mock(TemplateRenderer.class);
        configService = mock(AIReviewerConfigService.class);
        applicationPropertiesService = mock(ApplicationPropertiesService.class);
        request = mock(HttpServletRequest.class);
        response = mock(HttpServletResponse.class);

        when(applicationPropertiesService.getBaseUrl()).thenReturn(URI.create("https://bitbucket.example.local"));
        when(configService.getDefaultConfiguration()).thenReturn(Collections.emptyMap());
    }

    @Test
    public void doGetDoesNotLeakExceptionMessageAndIncludesCorrelationReference() throws Exception {
        UserProfile admin = mock(UserProfile.class);
        UserKey key = new UserKey("admin");
        when(admin.getUserKey()).thenReturn(key);
        when(admin.getUsername()).thenReturn("admin");
        when(userManager.getRemoteUser(request)).thenReturn(admin);
        when(userManager.isSystemAdmin(key)).thenReturn(true);
        when(configService.getConfigurationAsMap()).thenThrow(new RuntimeException("token=secret-value"));

        StringWriter stringWriter = new StringWriter();
        when(response.getWriter()).thenReturn(new PrintWriter(stringWriter));

        AdminConfigServlet servlet = new AdminConfigServlet(
                userManager,
                loginUriProvider,
                templateRenderer,
                configService,
                applicationPropertiesService);

        servlet.doGet(request, response);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> contextCaptor = ArgumentCaptor.forClass(Map.class);
        verify(templateRenderer).render(eq("/templates/admin-config.vm"), contextCaptor.capture(), any(PrintWriter.class));

        Map<String, Object> context = contextCaptor.getValue();
        String errorMessage = String.valueOf(context.get("errorMessage"));
        assertTrue(errorMessage.contains("Failed to load configuration. Reference:"));
        assertFalse(errorMessage.contains("token=secret-value"));
        assertTrue(context.containsKey("errorCorrelationId"));
    }

    @Test
    public void doGetKeepsForbiddenResponseForNonAdmin() throws Exception {
        UserProfile user = mock(UserProfile.class);
        UserKey key = new UserKey("user");
        when(user.getUserKey()).thenReturn(key);
        when(userManager.getRemoteUser(request)).thenReturn(user);
        when(userManager.isSystemAdmin(key)).thenReturn(false);

        AdminConfigServlet servlet = new AdminConfigServlet(
                userManager,
                loginUriProvider,
                templateRenderer,
                configService,
                applicationPropertiesService);

        servlet.doGet(request, response);

        verify(response).sendError(eq(HttpServletResponse.SC_FORBIDDEN), eq("You must be an administrator to access this page"));
        verify(templateRenderer, never()).render(any(), any(), any(PrintWriter.class));
    }
}
