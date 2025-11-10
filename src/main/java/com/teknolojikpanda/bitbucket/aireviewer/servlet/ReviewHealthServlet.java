package com.teknolojikpanda.bitbucket.aireviewer.servlet;

import com.atlassian.templaterenderer.TemplateRenderer;

import javax.inject.Inject;
import javax.inject.Named;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

@Named
public class ReviewHealthServlet extends HttpServlet {

    private final TemplateRenderer templateRenderer;

    @Inject
    public ReviewHealthServlet(TemplateRenderer templateRenderer) {
        this.templateRenderer = Objects.requireNonNull(templateRenderer, "templateRenderer");
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        resp.setContentType("text/html;charset=UTF-8");
        Map<String, Object> context = new HashMap<>();
        context.put("baseUrl", req.getContextPath());
        templateRenderer.render("/templates/admin-health.vm", context, resp.getWriter());
    }
}
