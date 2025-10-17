package com.example.bitbucket.aireviewer.servlet;

import com.atlassian.bitbucket.permission.Permission;
import com.atlassian.bitbucket.permission.PermissionService;
import com.atlassian.plugin.spring.scanner.annotation.imports.ComponentImport;
import com.atlassian.sal.api.auth.LoginUriProvider;
import com.atlassian.sal.api.user.UserKey;
import com.atlassian.sal.api.user.UserManager;
import com.atlassian.templaterenderer.TemplateRenderer;
import com.google.common.collect.ImmutableMap;
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
    private final PermissionService permissionService;

    @Inject
    public AdminConfigServlet(
            @ComponentImport UserManager userManager,
            @ComponentImport LoginUriProvider loginUriProvider,
            @ComponentImport TemplateRenderer templateRenderer,
            @ComponentImport PermissionService permissionService) {
        this.userManager = userManager;
        this.loginUriProvider = loginUriProvider;
        this.templateRenderer = templateRenderer;
        this.permissionService = permissionService;
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp)
            throws ServletException, IOException {

        // Check if user is authenticated
        String username = userManager.getRemoteUsername(req);
        if (username == null) {
            redirectToLogin(req, resp);
            return;
        }

        // Check if user is admin
        if (!userManager.isSystemAdmin(username)) {
            resp.sendError(HttpServletResponse.SC_FORBIDDEN,
                "You must be an administrator to access this page");
            return;
        }

        log.debug("Admin config page accessed by: {}", username);

        // Render the admin configuration page
        resp.setContentType("text/html;charset=UTF-8");

        Map<String, Object> context = new HashMap<>();
        context.put("username", username);
        context.put("baseUrl", getBaseUrl(req));

        // Default configuration values (will be replaced with actual config service later)
        context.put("ollamaUrl", "http://10.152.98.37:11434");
        context.put("ollamaModel", "qwen3-coder:30b");
        context.put("fallbackModel", "qwen3-coder:7b");
        context.put("maxCharsPerChunk", 60000);
        context.put("maxFilesPerChunk", 3);
        context.put("maxChunks", 20);
        context.put("parallelThreads", 4);
        context.put("connectTimeout", 10000);
        context.put("readTimeout", 30000);
        context.put("ollamaTimeout", 300000);
        context.put("maxIssuesPerFile", 50);
        context.put("maxIssueComments", 30);
        context.put("maxDiffSize", 10000000);
        context.put("maxRetries", 3);
        context.put("baseRetryDelay", 1000);
        context.put("apiDelay", 100);
        context.put("minSeverity", "medium");
        context.put("requireApprovalFor", "critical,high");
        context.put("reviewExtensions", "java,groovy,js,ts,tsx,jsx,py,go,rs,cpp,c,cs,php,rb,kt,swift,scala");
        context.put("ignorePatterns", "*.min.js,*.generated.*,package-lock.json,yarn.lock,*.map");
        context.put("ignorePaths", "node_modules/,vendor/,build/,dist/,.git/");
        context.put("enabled", true);
        context.put("reviewDraftPRs", false);
        context.put("skipGeneratedFiles", true);
        context.put("skipTests", false);

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
