package com.example.bitbucket.aireviewer.rest;

import com.atlassian.plugin.spring.scanner.annotation.imports.ComponentImport;
import com.atlassian.sal.api.user.UserManager;
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

    @Inject
    public ConfigResource(@ComponentImport UserManager userManager) {
        this.userManager = userManager;
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

        // TODO: Replace with actual configuration service
        // For now, return default configuration
        Map<String, Object> config = getDefaultConfiguration();

        return Response.ok(config).build();
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

        // TODO: Validate and save configuration using configuration service
        // For now, just return success

        return Response.ok(success("Configuration updated successfully")).build();
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

        // TODO: Implement actual connection test
        // For now, just return success if URL looks valid
        try {
            java.net.URL url = new java.net.URL(ollamaUrl);

            // Simple validation - just check URL is well-formed
            if (!url.getProtocol().equals("http") && !url.getProtocol().equals("https")) {
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity(error("URL must use HTTP or HTTPS protocol"))
                        .build();
            }

            // TODO: Actually test connection to Ollama API
            // For now, return success
            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("message", "Connection test passed (URL validation only)");
            result.put("note", "Full connection test will be implemented when Ollama client is added");

            return Response.ok(result).build();

        } catch (java.net.MalformedURLException e) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(error("Invalid URL format: " + e.getMessage()))
                    .build();
        }
    }

    /**
     * Get default configuration
     */
    private Map<String, Object> getDefaultConfiguration() {
        Map<String, Object> config = new HashMap<>();

        // Ollama Configuration
        config.put("ollamaUrl", "http://10.152.98.37:11434");
        config.put("ollamaModel", "qwen3-coder:30b");
        config.put("fallbackModel", "qwen3-coder:7b");

        // Processing Configuration
        config.put("maxCharsPerChunk", 60000);
        config.put("maxFilesPerChunk", 3);
        config.put("maxChunks", 20);
        config.put("parallelThreads", 4);

        // Timeout Configuration
        config.put("connectTimeout", 10000);
        config.put("readTimeout", 30000);
        config.put("ollamaTimeout", 300000);

        // Review Configuration
        config.put("maxIssuesPerFile", 50);
        config.put("maxIssueComments", 30);
        config.put("maxDiffSize", 10000000);

        // Retry Configuration
        config.put("maxRetries", 3);
        config.put("baseRetryDelay", 1000);
        config.put("apiDelay", 100);

        // Review Profile
        config.put("minSeverity", "medium");
        config.put("requireApprovalFor", "critical,high");

        // File Filtering
        config.put("reviewExtensions", "java,groovy,js,ts,tsx,jsx,py,go,rs,cpp,c,cs,php,rb,kt,swift,scala");
        config.put("ignorePatterns", "*.min.js,*.generated.*,package-lock.json,yarn.lock,*.map");
        config.put("ignorePaths", "node_modules/,vendor/,build/,dist/,.git/");

        // Feature Flags
        config.put("enabled", true);
        config.put("reviewDraftPRs", false);
        config.put("skipGeneratedFiles", true);
        config.put("skipTests", false);

        return config;
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
