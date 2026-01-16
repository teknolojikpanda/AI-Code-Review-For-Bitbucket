package com.teknolojikpanda.bitbucket.aireviewer.servlet;

import com.atlassian.bitbucket.permission.Permission;
import com.atlassian.bitbucket.permission.PermissionService;
import com.atlassian.bitbucket.repository.Repository;
import com.atlassian.bitbucket.repository.RepositoryService;
import com.atlassian.bitbucket.user.ApplicationUser;
import com.atlassian.bitbucket.user.UserService;
import com.atlassian.plugin.spring.scanner.annotation.imports.ComponentImport;
import com.atlassian.sal.api.auth.LoginUriProvider;
import com.atlassian.sal.api.user.UserManager;
import com.atlassian.sal.api.user.UserProfile;
import com.atlassian.sal.api.user.UserKey;
import com.atlassian.templaterenderer.TemplateRenderer;
import com.teknolojikpanda.bitbucket.aicode.model.PromptTemplates;
import com.teknolojikpanda.bitbucket.aireviewer.service.AIReviewerConfigService;

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
public class RepoPromptConfigServlet extends HttpServlet {

    private final UserManager userManager;
    private final LoginUriProvider loginUriProvider;
    private final TemplateRenderer templateRenderer;
    private final RepositoryService repositoryService;
    private final PermissionService permissionService;
    private final UserService userService;
    private final AIReviewerConfigService configService;

    @Inject
    public RepoPromptConfigServlet(@ComponentImport UserManager userManager,
                                   @ComponentImport LoginUriProvider loginUriProvider,
                                   @ComponentImport TemplateRenderer templateRenderer,
                                   @ComponentImport RepositoryService repositoryService,
                                   @ComponentImport PermissionService permissionService,
                                   @ComponentImport UserService userService,
                                   AIReviewerConfigService configService) {
        this.userManager = Objects.requireNonNull(userManager, "userManager");
        this.loginUriProvider = Objects.requireNonNull(loginUriProvider, "loginUriProvider");
        this.templateRenderer = Objects.requireNonNull(templateRenderer, "templateRenderer");
        this.repositoryService = Objects.requireNonNull(repositoryService, "repositoryService");
        this.permissionService = Objects.requireNonNull(permissionService, "permissionService");
        this.userService = Objects.requireNonNull(userService, "userService");
        this.configService = Objects.requireNonNull(configService, "configService");
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
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

        String projectKey = req.getParameter("projectKey");
        String repositorySlug = req.getParameter("repositorySlug");
        if (projectKey == null || repositorySlug == null) {
            resp.sendError(HttpServletResponse.SC_BAD_REQUEST, "Missing repository context");
            return;
        }

        Repository repository = repositoryService.getBySlug(projectKey, repositorySlug);
        if (repository == null) {
            resp.sendError(HttpServletResponse.SC_NOT_FOUND, "Repository not found");
            return;
        }

        req.setAttribute("repository", repository);
        req.setAttribute("project", repository.getProject());

        ApplicationUser user = userService.getUserBySlug(profile.getUsername());
        if (user == null) {
            resp.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Unable to resolve current user");
            return;
        }

        boolean hasRepoAdmin = permissionService.hasRepositoryPermission(user, repository, Permission.REPO_ADMIN);
        boolean hasProjectAdmin = permissionService.hasProjectPermission(user, repository.getProject(), Permission.PROJECT_ADMIN);
        boolean hasGlobalAdmin = permissionService.hasGlobalPermission(user, Permission.ADMIN);
        if (!hasRepoAdmin && !hasProjectAdmin && !hasGlobalAdmin) {
            resp.sendError(HttpServletResponse.SC_FORBIDDEN,
                    "You must be a repository or project admin to access this page");
            return;
        }

        resp.setContentType("text/html;charset=UTF-8");
        Map<String, Object> context = new HashMap<>();
        context.put("baseUrl", getBaseUrl(req));
        context.put("projectKey", repository.getProject().getKey());
        context.put("projectName", repository.getProject().getName());
        context.put("repositorySlug", repository.getSlug());
        context.put("repositoryName", repository.getName());
    context.put("repositoryId", repository.getId());
        context.put("username", profile.getUsername());
        context.put("configServiceAvailable", configService != null);
    PromptTemplates promptDefaults = PromptTemplates.loadDefaults();
    context.put("promptChunkDefault", promptDefaults.getChunkInstructionsTemplate());
        templateRenderer.render("/templates/repo-config.vm", context, resp.getWriter());
    }

    private void redirectToLogin(HttpServletRequest req, HttpServletResponse resp) throws IOException {
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
