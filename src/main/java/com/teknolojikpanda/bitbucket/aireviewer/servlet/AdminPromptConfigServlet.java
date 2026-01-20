package com.teknolojikpanda.bitbucket.aireviewer.servlet;

import com.atlassian.plugin.spring.scanner.annotation.imports.ComponentImport;
import com.atlassian.sal.api.auth.LoginUriProvider;
import com.atlassian.bitbucket.server.ApplicationPropertiesService;
import com.atlassian.sal.api.user.UserManager;
import com.atlassian.sal.api.user.UserProfile;
import com.atlassian.sal.api.user.UserKey;
import com.atlassian.templaterenderer.TemplateRenderer;
import com.teknolojikpanda.bitbucket.aicode.core.ReviewConfigFactory;
import com.teknolojikpanda.bitbucket.aicode.model.PromptTemplates;
import com.teknolojikpanda.bitbucket.aicode.model.ReviewConfig;
import com.teknolojikpanda.bitbucket.aireviewer.service.AIReviewerConfigService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.inject.Named;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

@Named
public class AdminPromptConfigServlet extends HttpServlet {

    private static final Logger log = LoggerFactory.getLogger(AdminPromptConfigServlet.class);

    private final UserManager userManager;
    private final LoginUriProvider loginUriProvider;
    private final TemplateRenderer templateRenderer;
    private final AIReviewerConfigService configService;
    private final ReviewConfigFactory configFactory;
    private final ApplicationPropertiesService applicationPropertiesService;

    @Inject
    public AdminPromptConfigServlet(@ComponentImport UserManager userManager,
                                    @ComponentImport LoginUriProvider loginUriProvider,
                                    @ComponentImport TemplateRenderer templateRenderer,
                                    AIReviewerConfigService configService,
                                    ReviewConfigFactory configFactory,
                                    @ComponentImport ApplicationPropertiesService applicationPropertiesService) {
        this.userManager = Objects.requireNonNull(userManager, "userManager");
        this.loginUriProvider = Objects.requireNonNull(loginUriProvider, "loginUriProvider");
        this.templateRenderer = Objects.requireNonNull(templateRenderer, "templateRenderer");
        this.configService = Objects.requireNonNull(configService, "configService");
        this.configFactory = Objects.requireNonNull(configFactory, "configFactory");
        this.applicationPropertiesService = Objects.requireNonNull(applicationPropertiesService, "applicationPropertiesService");
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp)
            throws ServletException, IOException {
        UserProfile profile = userManager.getRemoteUser(req);
        if (profile == null) {
            redirectToLogin(req, resp);
            return;
        }

        UserKey userKey = profile.getUserKey();
        if (userKey == null) {
            resp.sendError(HttpServletResponse.SC_UNAUTHORIZED,
                    "Unable to determine current user. Please authenticate again.");
            return;
        }

        if (!userManager.isSystemAdmin(userKey)) {
            resp.sendError(HttpServletResponse.SC_FORBIDDEN,
                    "You must be an administrator to access this page");
            return;
        }

        log.debug("Admin prompt config page accessed by: {}", profile.getUsername());

        resp.setContentType("text/html;charset=UTF-8");
        Map<String, Object> context = new HashMap<>();
        context.put("username", profile.getUsername());
        context.put("baseUrl", getBaseUrl(req));

        Map<String, Object> defaults = new HashMap<>(configService.getDefaultConfiguration());
        Map<String, Object> configValues = new HashMap<>(defaults);

        try {
            configValues.putAll(configService.getConfigurationAsMap());
        } catch (Exception e) {
            log.error("Failed to load configuration for prompt UI", e);
            context.put("errorMessage", "Failed to load configuration: " + e.getMessage());
        }

        PromptTemplates promptDefaults = PromptTemplates.loadDefaults();
        context.put("promptSystemDefault", promptDefaults.getSystemPrompt());
        context.put("promptChunkDefault", promptDefaults.getChunkInstructionsTemplate());
        context.put("promptChunkOverride", configValues.get("prompt.chunk"));
        context.put("promptSystemAppend", configValues.get("prompt.system.append"));

        ReviewConfig reviewConfig = configFactory.from(configValues);
        PromptTemplates effectiveTemplates = reviewConfig.getPromptTemplates();
        context.put("promptSystemEffective", effectiveTemplates.getSystemPrompt());
        context.put("promptChunkEffective", effectiveTemplates.getChunkInstructionsTemplate());

        templateRenderer.render("/templates/admin-prompts.vm", context, resp.getWriter());
    }

    private void redirectToLogin(HttpServletRequest req, HttpServletResponse resp)
            throws IOException {
        URI loginUri = loginUriProvider.getLoginUri(getUri(req));
        resp.sendRedirect(loginUri.toASCIIString());
    }

    private URI getUri(HttpServletRequest req) {
        StringBuffer builder = req.getRequestURL();
        if (req.getQueryString() != null) {
            builder.append("?").append(req.getQueryString());
        }
        return URI.create(builder.toString());
    }

    private String getBaseUrl(HttpServletRequest req) {
        java.net.URI baseUrl = applicationPropertiesService.getBaseUrl();
        if (baseUrl != null && !baseUrl.toString().trim().isEmpty()) {
            return baseUrl.toString();
        }
        return req.getScheme() + "://" + req.getServerName() +
               ":" + req.getServerPort() + req.getContextPath();
    }
}
