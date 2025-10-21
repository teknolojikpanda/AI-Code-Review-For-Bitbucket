package com.example.bitbucket.aireviewer.rest;

import com.atlassian.plugin.spring.scanner.annotation.imports.ComponentImport;
import com.atlassian.sal.api.user.UserManager;
import com.example.bitbucket.aireviewer.service.AIReviewerConfigService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.inject.Named;
import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.*;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.util.HashMap;
import java.util.Map;

/**
 * REST API resource for managing AI Reviewer configuration.
 * Provides endpoints for reading and updating plugin configuration.
 */
@Path("/config")
@Named
public class ConfigResource {

    private static final Logger log = LoggerFactory.getLogger(ConfigResource.class);

    private final UserManager userManager;
    private final AIReviewerConfigService configService;

    @Inject
    public ConfigResource(
            @ComponentImport UserManager userManager,
            AIReviewerConfigService configService) {
        this.userManager = userManager;
        this.configService = configService;
    }

    /**
     * Get current configuration
     *
     * GET /rest/ai-reviewer/1.0/config
     */
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public Response getConfiguration(@Context HttpServletRequest request) {
        String username = userManager.getRemoteUsername(request);

        if (username == null || !userManager.isSystemAdmin(username)) {
            return Response.status(Response.Status.FORBIDDEN)
                    .entity(error("Access denied. Administrator privileges required."))
                    .build();
        }

        log.debug("Getting configuration for user: {}", username);

        try {
            Map<String, Object> config = configService.getConfigurationAsMap();
            return Response.ok(config).build();
        } catch (Exception e) {
            log.error("Error getting configuration", e);
            return Response.serverError()
                    .entity(error("Failed to get configuration: " + e.getMessage()))
                    .build();
        }
    }

    /**
     * Update configuration
     *
     * PUT /rest/ai-reviewer/1.0/config
     */
    @PUT
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response updateConfiguration(
            Map<String, Object> config,
            @Context HttpServletRequest request) {

        String username = userManager.getRemoteUsername(request);

        if (username == null || !userManager.isSystemAdmin(username)) {
            return Response.status(Response.Status.FORBIDDEN)
                    .entity(error("Access denied. Administrator privileges required."))
                    .build();
        }

        log.info("Updating configuration by user: {}", username);
        log.debug("New configuration: {}", config);

        try {
            configService.updateConfiguration(config);
            return Response.ok(success("Configuration updated successfully")).build();
        } catch (IllegalArgumentException e) {
            log.warn("Invalid configuration: {}", e.getMessage());
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(error("Invalid configuration: " + e.getMessage()))
                    .build();
        } catch (Exception e) {
            log.error("Error updating configuration", e);
            return Response.serverError()
                    .entity(error("Failed to update configuration: " + e.getMessage()))
                    .build();
        }
    }

    /**
     * Test connection to Ollama
     *
     * POST /rest/ai-reviewer/1.0/config/test-connection
     */
    @POST
    @Path("/test-connection")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response testConnection(
            Map<String, String> params,
            @Context HttpServletRequest request) {

        String username = userManager.getRemoteUsername(request);

        if (username == null || !userManager.isSystemAdmin(username)) {
            return Response.status(Response.Status.FORBIDDEN)
                    .entity(error("Access denied. Administrator privileges required."))
                    .build();
        }

        String ollamaUrl = params.get("ollamaUrl");
        if (ollamaUrl == null || ollamaUrl.trim().isEmpty()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(error("Ollama URL is required"))
                    .build();
        }

        log.info("Testing connection to Ollama: {}", ollamaUrl);

        try {
            boolean success = configService.testOllamaConnection(ollamaUrl);

            if (success) {
                Map<String, Object> result = new HashMap<>();
                result.put("success", true);
                result.put("message", "Connection successful!");
                return Response.ok(result).build();
            } else {
                return Response.status(Response.Status.BAD_REQUEST)
                    .entity(error("Connection test failed. Unable to reach Ollama server at " + ollamaUrl))
                    .build();
            }

        } catch (IllegalArgumentException e) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(error("Invalid URL: " + e.getMessage()))
                    .build();
        } catch (Exception e) {
            log.error("Error testing connection", e);
            return Response.serverError()
                    .entity(error("Connection test failed: " + e.getMessage()))
                    .build();
        }
    }

    /**
     * Create error response
     */
    private Map<String, String> error(String message) {
        Map<String, String> error = new HashMap<>();
        error.put("error", message);
        return error;
    }

    /**
     * Create success response
     */
    private Map<String, String> success(String message) {
        Map<String, String> success = new HashMap<>();
        success.put("message", message);
        return success;
    }
}
