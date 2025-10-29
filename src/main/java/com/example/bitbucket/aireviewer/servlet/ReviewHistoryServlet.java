package com.example.bitbucket.aireviewer.servlet;

import com.atlassian.plugin.spring.scanner.annotation.imports.ComponentImport;
import com.atlassian.sal.api.auth.LoginUriProvider;
import com.atlassian.sal.api.user.UserKey;
import com.atlassian.sal.api.user.UserManager;
import com.atlassian.sal.api.user.UserProfile;
import com.atlassian.templaterenderer.TemplateRenderer;
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
 * Servlet rendering the AI review history administration page.
 */
@Named
public class ReviewHistoryServlet extends HttpServlet {

    private static final Logger log = LoggerFactory.getLogger(ReviewHistoryServlet.class);

    private final UserManager userManager;
    private final LoginUriProvider loginUriProvider;
    private final TemplateRenderer templateRenderer;

    @Inject
    public ReviewHistoryServlet(@ComponentImport UserManager userManager,
                                @ComponentImport LoginUriProvider loginUriProvider,
                                @ComponentImport TemplateRenderer templateRenderer) {
        this.userManager = Objects.requireNonNull(userManager, "userManager");
        this.loginUriProvider = Objects.requireNonNull(loginUriProvider, "loginUriProvider");
        this.templateRenderer = Objects.requireNonNull(templateRenderer, "templateRenderer");
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        UserProfile profile = userManager.getRemoteUser(req);
        if (profile == null) {
            redirectToLogin(req, resp);
            return;
        }

        UserKey userKey = profile.getUserKey();
        if (userKey == null || !userManager.isSystemAdmin(userKey)) {
            resp.sendError(HttpServletResponse.SC_FORBIDDEN,
                    "You must be an administrator to access this page");
            return;
        }

        String username = profile.getUsername();
        log.debug("Review history page accessed by: {}", username);

        resp.setContentType("text/html;charset=UTF-8");
        Map<String, Object> context = new HashMap<>();
        context.put("username", username);
        context.put("baseUrl", getBaseUrl(req));
        templateRenderer.render("/templates/admin-history.vm", context, resp.getWriter());
    }

    private void redirectToLogin(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        URI loginUri = loginUriProvider.getLoginUri(getUri(req));
        resp.sendRedirect(loginUri.toASCIIString());
    }

    private URI getUri(HttpServletRequest req) {
        StringBuffer builder = req.getRequestURL();
        if (req.getQueryString() != null) {
            builder.append('?').append(req.getQueryString());
        }
        return URI.create(builder.toString());
    }

    private String getBaseUrl(HttpServletRequest req) {
        return req.getScheme() + "://" + req.getServerName() +
                ":" + req.getServerPort() + req.getContextPath();
    }
}
