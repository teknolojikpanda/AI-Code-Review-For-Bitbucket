package com.example.bitbucket.aireviewer.servlet;

import com.atlassian.plugin.spring.scanner.annotation.imports.ComponentImport;
import com.atlassian.sal.api.auth.LoginUriProvider;
import com.atlassian.sal.api.user.UserManager;
import com.atlassian.sal.api.user.UserProfile;
import com.atlassian.sal.api.user.UserKey;
import com.atlassian.templaterenderer.TemplateRenderer;
import com.example.bitbucket.aicode.model.ReviewProfilePreset;
import com.example.bitbucket.aireviewer.service.AIReviewerConfigService;
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

/**
 * Admin configuration servlet for AI Code Reviewer plugin.
 * Provides a web UI for configuring plugin settings.
 */
@Named
public class AdminConfigServlet extends HttpServlet {

    private static final Logger log = LoggerFactory.getLogger(AdminConfigServlet.class);

    private final UserManager userManager;
    private final LoginUriProvider loginUriProvider;
    private final TemplateRenderer templateRenderer;
    private final AIReviewerConfigService configService;

    @Inject
    public AdminConfigServlet(
            @ComponentImport UserManager userManager,
            @ComponentImport LoginUriProvider loginUriProvider,
            @ComponentImport TemplateRenderer templateRenderer,
            AIReviewerConfigService configService) {
        this.userManager = Objects.requireNonNull(userManager, "userManager");
        this.loginUriProvider = Objects.requireNonNull(loginUriProvider, "loginUriProvider");
        this.templateRenderer = Objects.requireNonNull(templateRenderer, "templateRenderer");
        this.configService = Objects.requireNonNull(configService, "configService");
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp)
            throws ServletException, IOException {

        // Check if user is authenticated
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

        // Check if user is admin
        if (!userManager.isSystemAdmin(userKey)) {
            resp.sendError(HttpServletResponse.SC_FORBIDDEN,
                "You must be an administrator to access this page");
            return;
        }

        String username = profile.getUsername();

        log.debug("Admin config page accessed by: {}", username);

        // Render the admin configuration page
        resp.setContentType("text/html;charset=UTF-8");

        Map<String, Object> context = new HashMap<>();
        context.put("username", username);
        context.put("baseUrl", getBaseUrl(req));

        Map<String, Object> defaults = new HashMap<>(configService.getDefaultConfiguration());
        Map<String, Object> configValues = new HashMap<>(defaults);

        try {
            configValues.putAll(configService.getConfigurationAsMap());
        } catch (Exception e) {
            log.error("Failed to load configuration for admin UI", e);
            context.put("errorMessage", "Failed to load configuration: " + e.getMessage());
        }

        context.put("ollamaUrl", configValues.get("ollamaUrl"));
        context.put("ollamaModel", configValues.get("ollamaModel"));
        context.put("fallbackModel", configValues.get("fallbackModel"));
        context.put("maxCharsPerChunk", configValues.get("maxCharsPerChunk"));
        context.put("maxFilesPerChunk", configValues.get("maxFilesPerChunk"));
        context.put("maxChunks", configValues.get("maxChunks"));
        context.put("parallelThreads", configValues.get("parallelThreads"));
        context.put("connectTimeout", configValues.get("connectTimeout"));
        context.put("readTimeout", configValues.get("readTimeout"));
        context.put("ollamaTimeout", configValues.get("ollamaTimeout"));
        context.put("maxIssuesPerFile", configValues.get("maxIssuesPerFile"));
        context.put("maxIssueComments", configValues.get("maxIssueComments"));
        context.put("maxDiffSize", configValues.get("maxDiffSize"));
        context.put("maxRetries", configValues.get("maxRetries"));
        context.put("baseRetryDelay", configValues.get("baseRetryDelay"));
        context.put("apiDelay", configValues.getOrDefault("apiDelayMs", defaults.get("apiDelayMs")));
        context.put("minSeverity", configValues.get("minSeverity"));
        context.put("requireApprovalFor", configValues.get("requireApprovalFor"));
        context.put("reviewExtensions", configValues.get("reviewExtensions"));
        context.put("ignorePatterns", configValues.get("ignorePatterns"));
        context.put("ignorePaths", configValues.get("ignorePaths"));
        context.put("profileOptions", ReviewProfilePreset.descriptors());
        context.put("selectedProfile", configValues.getOrDefault("reviewProfile", defaults.get("reviewProfile")));
        context.put("enabled", configValues.get("enabled"));
        context.put("reviewDraftPRs", configValues.get("reviewDraftPRs"));
        context.put("skipGeneratedFiles", configValues.get("skipGeneratedFiles"));
        context.put("skipTests", configValues.get("skipTests"));
        context.put("autoApprove", configValues.get("autoApprove"));
        templateRenderer.render("/templates/admin-config.vm", context, resp.getWriter());
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
        return req.getScheme() + "://" + req.getServerName() +
               ":" + req.getServerPort() + req.getContextPath();
    }
}
