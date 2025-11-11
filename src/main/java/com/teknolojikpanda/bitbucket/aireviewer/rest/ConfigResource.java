package com.teknolojikpanda.bitbucket.aireviewer.rest;

import com.atlassian.bitbucket.user.ApplicationUser;
import com.atlassian.bitbucket.user.UserSearchRequest;
import com.atlassian.bitbucket.user.UserService;
import com.atlassian.bitbucket.util.Page;
import com.atlassian.bitbucket.util.PageRequest;
import com.atlassian.bitbucket.util.PageRequestImpl;
import com.atlassian.plugin.spring.scanner.annotation.imports.ComponentImport;
import com.atlassian.sal.api.user.UserKey;
import com.atlassian.sal.api.user.UserManager;
import com.atlassian.sal.api.user.UserProfile;
import com.teknolojikpanda.bitbucket.aicode.model.ReviewProfilePreset;
import com.teknolojikpanda.bitbucket.aireviewer.service.AIReviewerConfigService;
import com.teknolojikpanda.bitbucket.aireviewer.service.AIReviewerConfigService.ScopeMode;
import com.teknolojikpanda.bitbucket.aireviewer.service.ConfigurationValidationException;
import com.teknolojikpanda.bitbucket.aireviewer.service.AIReviewerConfigService.RepositoryCatalogPage;
import com.teknolojikpanda.bitbucket.aireviewer.service.GuardrailsRateLimitOverrideService;
import com.teknolojikpanda.bitbucket.aireviewer.service.GuardrailsRateLimitOverrideService.OverrideRecord;
import com.teknolojikpanda.bitbucket.aireviewer.service.GuardrailsRateLimitScope;
import com.teknolojikpanda.bitbucket.aireviewer.service.GuardrailsRateLimitStore;
import com.teknolojikpanda.bitbucket.aireviewer.service.GuardrailsRateLimitStore.ThrottleIncident;
import com.teknolojikpanda.bitbucket.aireviewer.service.ReviewRateLimiter;
import com.teknolojikpanda.bitbucket.aireviewer.service.ReviewRateLimiter.RateLimitSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.inject.Named;
import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.*;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * REST API resource for managing AI Reviewer configuration.
 * Provides endpoints for reading and updating plugin configuration.
 */
@Path("/config")
@Named
public class ConfigResource {

    private static final Logger log = LoggerFactory.getLogger(ConfigResource.class);
    private static final int MAX_SCOPE_SELECTION = 1000;
    private static final Pattern PROJECT_KEY_PATTERN = Pattern.compile("^[A-Z0-9_\\-]+$");
    private static final Pattern REPOSITORY_SLUG_PATTERN = Pattern.compile("^[A-Za-z0-9._\\-]+$");
    private static final RateLimiter RATE_LIMITER = new RateLimiter();
    private static final int HTTP_TOO_MANY_REQUESTS = 429;
    private static final long CONFIG_WRITE_WINDOW_MS = TimeUnit.MINUTES.toMillis(1);
    private static final int CONFIG_WRITE_LIMIT = 12;
    private static final long TEST_CONNECTION_WINDOW_MS = TimeUnit.MINUTES.toMillis(1);
    private static final int TEST_CONNECTION_LIMIT = 4;
    private static final long USER_SEARCH_WINDOW_MS = TimeUnit.SECONDS.toMillis(60);
    private static final int USER_SEARCH_LIMIT = 40;
    private static final int USER_SEARCH_PAGE_LIMIT = 25;
    private static final int LIMITER_SNAPSHOT_BUCKETS = 10;
    private static final long MAX_OVERRIDE_DURATION_MINUTES = TimeUnit.DAYS.toMinutes(7);
    private static final int RECENT_LIMITER_INCIDENTS = 40;

    private final UserManager userManager;
    private final UserService userService;
    private final AIReviewerConfigService configService;
    private final ReviewRateLimiter rateLimiter;
    private final GuardrailsRateLimitOverrideService overrideService;
    private final GuardrailsRateLimitStore rateLimitStore;

    @Inject
    public ConfigResource(
            @ComponentImport UserManager userManager,
            @ComponentImport UserService userService,
            AIReviewerConfigService configService,
            ReviewRateLimiter rateLimiter,
            GuardrailsRateLimitOverrideService overrideService,
            GuardrailsRateLimitStore rateLimitStore) {
        this.userManager = userManager;
        this.userService = userService;
        this.configService = configService;
        this.rateLimiter = Objects.requireNonNull(rateLimiter, "rateLimiter");
        this.overrideService = Objects.requireNonNull(overrideService, "overrideService");
        this.rateLimitStore = Objects.requireNonNull(rateLimitStore, "rateLimitStore");
    }

    /**
     * Get current configuration
     *
     * GET /rest/ai-reviewer/1.0/config
     */
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public Response getConfiguration(@Context HttpServletRequest request) {
        UserProfile profile = userManager.getRemoteUser(request);

        if (!isSystemAdmin(profile)) {
            return Response.status(Response.Status.FORBIDDEN)
                    .entity(error("Access denied. Administrator privileges required."))
                    .build();
        }

        String username = profile.getUsername();
        log.debug("Getting configuration for user: {}", username);

        try {
            Map<String, Object> config = new HashMap<>(configService.getConfigurationAsMap());
            Map<String, Object> defaults = configService.getDefaultConfiguration();
            Object defaultApiDelay = defaults.get("apiDelayMs");
            config.putIfAbsent("apiDelay", config.getOrDefault("apiDelayMs", defaultApiDelay));
            config.put("profilePresets", ReviewProfilePreset.descriptors());
            config.put("repositoryOverrides", configService.listRepositoryConfigurations());
            config.put("limiter", limiterSnapshot());
            config.put("rateLimitOverrides", overridesToList(overrideService.listOverrides(false)));
            config.put("rateLimitIncidents", incidentsToList(rateLimitStore.fetchRecentIncidents(RECENT_LIMITER_INCIDENTS)));
            return Response.ok(config).build();
        } catch (Exception e) {
            log.error("Error getting configuration", e);
            return Response.serverError()
                    .entity(error("Failed to get configuration: " + e.getMessage()))
                    .build();
        }
    }

    @GET
    @Path("/limiter")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getLimiterDetails(@Context HttpServletRequest request) {
        UserProfile profile = userManager.getRemoteUser(request);
        if (!isSystemAdmin(profile)) {
            return Response.status(Response.Status.FORBIDDEN)
                    .entity(error("Access denied. Administrator privileges required."))
                    .build();
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("snapshot", limiterSnapshot());
        payload.put("overrides", overridesToList(overrideService.listOverrides(false)));
        payload.put("incidents", incidentsToList(rateLimitStore.fetchRecentIncidents(RECENT_LIMITER_INCIDENTS)));
        return Response.ok(payload).build();
    }

    @POST
    @Path("/limiter/overrides")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response createOverride(Map<String, Object> payload,
                                   @Context HttpServletRequest request) {
        UserProfile profile = userManager.getRemoteUser(request);
        if (!isSystemAdmin(profile)) {
            return Response.status(Response.Status.FORBIDDEN)
                    .entity(error("Access denied. Administrator privileges required."))
                    .build();
        }
        Response rateLimited = enforceRateLimit(request, profile,
                "limiter-override",
                "limiter overrides",
                CONFIG_WRITE_LIMIT,
                CONFIG_WRITE_WINDOW_MS);
        if (rateLimited != null) {
            return rateLimited;
        }
        try {
            GuardrailsRateLimitScope scope = parseOverrideScope(payload.get("scope"));
            String identifier = normalizeOverrideIdentifier(scope, payload.get("identifier"));
            int limitPerHour = parsePositiveInt(payload.get("limitPerHour"), "limitPerHour");
            long expiresAt = resolveOverrideExpiry(payload);
            String reason = stringValue(payload.get("reason"));
            String userKey = profile != null && profile.getUserKey() != null
                    ? profile.getUserKey().getStringValue()
                    : null;
            String displayName = profile != null ? profile.getFullName() : null;
            OverrideRecord record = overrideService.upsertOverride(
                    scope,
                    identifier,
                    limitPerHour,
                    expiresAt,
                    reason,
                    userKey,
                    displayName);
            return Response.ok(overrideToMap(record)).build();
        } catch (IllegalArgumentException ex) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(error(ex.getMessage()))
                    .build();
        }
    }

    @DELETE
    @Path("/limiter/overrides/{id}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response deleteOverride(@PathParam("id") int id,
                                   @Context HttpServletRequest request) {
        UserProfile profile = userManager.getRemoteUser(request);
        if (!isSystemAdmin(profile)) {
            return Response.status(Response.Status.FORBIDDEN)
                    .entity(error("Access denied. Administrator privileges required."))
                    .build();
        }
        if (id <= 0) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(error("Override id must be positive"))
                    .build();
        }
        java.util.Optional<OverrideRecord> existing = overrideService.getOverride(id);
        if (existing.isEmpty()) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(error("Override " + id + " not found"))
                    .build();
        }
        overrideService.deleteOverride(id);
        return Response.ok(success("Override removed")).build();
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

        UserProfile profile = userManager.getRemoteUser(request);

        if (!isSystemAdmin(profile)) {
            return Response.status(Response.Status.FORBIDDEN)
                    .entity(error("Access denied. Administrator privileges required."))
                    .build();
        }

        String username = profile.getUsername();
        Response rateLimited = enforceRateLimit(request, profile,
                "config-update",
                "configuration updates",
                CONFIG_WRITE_LIMIT,
                CONFIG_WRITE_WINDOW_MS);
        if (rateLimited != null) {
            return rateLimited;
        }
        log.info("Updating configuration by user: {}", username);
        log.debug("New configuration: {}", config);

        try {
            Map<String, Object> normalized = normalizeConfigPayload(config);
            configService.updateConfiguration(normalized);
            return Response.ok(success("Configuration updated successfully")).build();
        } catch (ConfigurationValidationException e) {
            log.warn("Invalid configuration: {}", e.getErrors());
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(error("Invalid configuration", e.getErrors()))
                    .build();
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

    @GET
    @Path("/repository-catalog")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getRepositoryCatalog(@Context HttpServletRequest request,
                                         @QueryParam("start") @DefaultValue("0") int start,
                                         @QueryParam("limit") @DefaultValue("50") int limit) {
        UserProfile profile = userManager.getRemoteUser(request);

        if (!isSystemAdmin(profile)) {
            return Response.status(Response.Status.FORBIDDEN)
                    .entity(error("Access denied. Administrator privileges required."))
                    .build();
        }
        try {
            int safeStart = Math.max(0, start);
            int safeLimit = Math.max(0, limit);

            RepositoryCatalogPage page = configService.getRepositoryCatalog(safeStart, safeLimit);

            Map<String, Object> payload = new HashMap<>();
            payload.put("projects", new ArrayList<>(page.getProjects()));
            payload.put("total", page.getTotal());
            payload.put("start", page.getStart());
            payload.put("limit", page.getLimit());
            return Response.ok(payload).build();
        } catch (Exception e) {
            log.error("Error loading repository catalog", e);
            return Response.serverError()
                    .entity(error("Failed to load repository catalog: " + e.getMessage()))
                    .build();
        }
    }

    @GET
    @Path("/users")
    @Produces(MediaType.APPLICATION_JSON)
    public Response searchUsers(@Context HttpServletRequest request,
                                @QueryParam("q") String query,
                                @QueryParam("limit") @DefaultValue("10") int limit) {
        UserProfile profile = userManager.getRemoteUser(request);

        if (!isSystemAdmin(profile)) {
            return Response.status(Response.Status.FORBIDDEN)
                    .entity(error("Access denied. Administrator privileges required."))
                    .build();
        }

        Response limited = enforceRateLimit(request, profile,
                "user-search",
                "user search requests",
                USER_SEARCH_LIMIT,
                USER_SEARCH_WINDOW_MS);
        if (limited != null) {
            return limited;
        }

        int safeLimit = Math.max(1, Math.min(limit, USER_SEARCH_PAGE_LIMIT));
        String filter = query == null ? "" : query.trim();

        try {
            UserSearchRequest.Builder builder = new UserSearchRequest.Builder();
            if (!filter.isEmpty()) {
                builder.filter(filter);
            }

            UserSearchRequest searchRequest = builder.build();
            PageRequest pageRequest = new PageRequestImpl(0, safeLimit);
            Page<ApplicationUser> page = userService.search(searchRequest, pageRequest);

            List<Map<String, Object>> users = new ArrayList<>();
            if (page != null) {
                for (ApplicationUser user : page.getValues()) {
                    if (user == null) {
                        continue;
                    }
                    Map<String, Object> descriptor = new HashMap<>();
                    descriptor.put("slug", user.getSlug());
                    descriptor.put("name", user.getName());
                    descriptor.put("displayName", user.getDisplayName());
                    descriptor.put("email", user.getEmailAddress());
                    users.add(descriptor);
                }
            }

            Map<String, Object> payload = new HashMap<>();
            payload.put("users", users);
            return Response.ok(payload).build();
        } catch (Exception e) {
            log.error("Error searching users for query '{}'", filter, e);
            return Response.serverError()
                    .entity(error("Failed to search users: " + e.getMessage()))
                    .build();
        }
    }

    @POST
    @Path("/scope")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response updateRepositoryScope(Map<String, Object> payload,
                                          @Context HttpServletRequest request) {
        UserProfile profile = userManager.getRemoteUser(request);
        if (!isSystemAdmin(profile)) {
            return Response.status(Response.Status.FORBIDDEN)
                    .entity(error("Access denied. Administrator privileges required."))
                    .build();
        }
        Response rateLimited = enforceRateLimit(request, profile,
                "scope-update",
                "scope updates",
                CONFIG_WRITE_LIMIT,
                CONFIG_WRITE_WINDOW_MS);
        if (rateLimited != null) {
            return rateLimited;
        }
        if (payload == null) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(error("Request payload is required"))
                    .build();
        }

        String username = profile.getUsername();
        String mode = stringValue(payload.get("mode"));
        String normalizedMode = mode != null ? mode.toLowerCase(Locale.ENGLISH) : "";
        if (!"all".equals(normalizedMode) && !"repositories".equals(normalizedMode)) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(error("Invalid scope mode. Expected 'all' or 'repositories'."))
                    .build();
        }
        ScopeMode scopeMode = ScopeMode.fromString(normalizedMode);

        Object repositoriesValue = payload.get("repositories");
        Collection<?> rawRepositories = (repositoriesValue instanceof Collection)
                ? (Collection<?>) repositoriesValue
                : Collections.emptyList();

        List<AIReviewerConfigService.RepositoryScope> scopes = new ArrayList<>();
        if ("repositories".equals(normalizedMode)) {
            Set<String> seen = new LinkedHashSet<>();
            for (Object entry : rawRepositories) {
                if (!(entry instanceof Map)) {
                    return Response.status(Response.Status.BAD_REQUEST)
                            .entity(error("Malformed repository entry in scope payload."))
                            .build();
                }
                Map<?, ?> repo = (Map<?, ?>) entry;
                String projectKey = stringValue(repo.get("projectKey"));
                String repositorySlug = stringValue(repo.get("repositorySlug"));
                if (projectKey == null || repositorySlug == null) {
                    return Response.status(Response.Status.BAD_REQUEST)
                            .entity(error("Each repository entry must include projectKey and repositorySlug."))
                            .build();
                }
                if (!PROJECT_KEY_PATTERN.matcher(projectKey).matches()) {
                    return Response.status(Response.Status.BAD_REQUEST)
                            .entity(error("Invalid project key: " + projectKey))
                            .build();
                }
                if (!REPOSITORY_SLUG_PATTERN.matcher(repositorySlug).matches()) {
                    return Response.status(Response.Status.BAD_REQUEST)
                            .entity(error("Invalid repository slug: " + repositorySlug))
                            .build();
                }
                String key = projectKey + "/" + repositorySlug;
                if (seen.add(key)) {
                    scopes.add(new AIReviewerConfigService.RepositoryScope(projectKey, repositorySlug));
                }
            }
        }

        if (scopes.size() > MAX_SCOPE_SELECTION) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(error("Too many repositories selected (max " + MAX_SCOPE_SELECTION + ")"))
                    .build();
        }

        try {
            configService.synchronizeRepositoryOverrides(scopes, scopeMode, username);
            Map<String, Object> response = new HashMap<>();
            response.put("repositoryOverrides", configService.listRepositoryConfigurations());
            response.put("mode", scopeMode == ScopeMode.ALL ? "all" : "repositories");
            response.put("selectedRepositories", scopes);
            return Response.ok(response).build();
        } catch (Exception e) {
            log.error("Failed to update repository scope", e);
            return Response.serverError()
                    .entity(error("Failed to update repository scope: " + e.getMessage()))
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

        UserProfile profile = userManager.getRemoteUser(request);

        if (!isSystemAdmin(profile)) {
            return Response.status(Response.Status.FORBIDDEN)
                    .entity(error("Access denied. Administrator privileges required."))
                    .build();
        }

        Response rateLimited = enforceRateLimit(request, profile,
                "connection-test",
                "connection test",
                TEST_CONNECTION_LIMIT,
                TEST_CONNECTION_WINDOW_MS);
        if (rateLimited != null) {
            return rateLimited;
        }

        String ollamaUrl = params.get("ollamaUrl");
        if (ollamaUrl == null || ollamaUrl.trim().isEmpty()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(error("Ollama URL is required"))
                    .build();
        }

        // Validate URL to prevent SSRF attacks
        if (!isValidOllamaUrl(ollamaUrl)) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(error("Invalid URL format or forbidden host"))
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
                    .entity(error("Connection test failed. Unable to reach Ollama server"))
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
     * Toggle the global auto-approve setting.
     *
     * POST /rest/ai-reviewer/1.0/config/auto-approve
     */
    @POST
    @Path("/auto-approve")
    @Produces(MediaType.APPLICATION_JSON)
    public Response toggleAutoApprove(Map<String, Object> payload,
                                      @Context HttpServletRequest request) {
        UserProfile profile = userManager.getRemoteUser(request);
        if (!isSystemAdmin(profile)) {
            return Response.status(Response.Status.FORBIDDEN)
                    .entity(error("Access denied. Administrator privileges required."))
                    .build();
        }

        Response rateLimited = enforceRateLimit(request, profile,
                "auto-approve-toggle",
                "auto-approve changes",
                CONFIG_WRITE_LIMIT,
                CONFIG_WRITE_WINDOW_MS);
        if (rateLimited != null) {
            return rateLimited;
        }

        if (payload == null || !payload.containsKey("enabled")) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(error("Request must include 'enabled' boolean value"))
                    .build();
        }

        Object rawValue = payload.get("enabled");
        Boolean enabled = parseBoolean(rawValue);
        if (enabled == null) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(error("Unable to parse 'enabled' value as boolean"))
                    .build();
        }

        try {
            Map<String, Object> update = new HashMap<>();
            update.put("autoApprove", enabled);
            configService.updateConfiguration(update);

            Map<String, Object> response = new HashMap<>();
            response.put("message", enabled
                    ? "Auto-approve enabled"
                    : "Auto-approve disabled");
            response.put("autoApprove", enabled);
            return Response.ok(response).build();
        } catch (ConfigurationValidationException ex) {
            log.warn("Invalid auto-approve toggle request: {}", ex.getErrors());
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(error("Invalid configuration", ex.getErrors()))
                    .build();
        } catch (IllegalArgumentException ex) {
            log.warn("Invalid auto-approve toggle request: {}", ex.getMessage());
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(error("Invalid configuration: " + ex.getMessage()))
                    .build();
        } catch (Exception ex) {
            log.error("Failed to toggle auto-approve", ex);
            return Response.serverError()
                    .entity(error("Failed to update auto-approve setting: " + ex.getMessage()))
                    .build();
        }
    }

    /**
     * Validates Ollama URL to prevent SSRF attacks
     */
    private boolean isValidOllamaUrl(String url) {
        try {
            java.net.URI parsedUri = java.net.URI.create(url);
            String scheme = parsedUri.getScheme();
            if (scheme == null) {
                return false;
            }
            String protocol = scheme.toLowerCase();
            return ("http".equals(protocol) || "https".equals(protocol)) && parsedUri.getHost() != null;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean isSystemAdmin(UserProfile profile) {
        if (profile == null) {
            return false;
        }
        UserKey key = profile.getUserKey();
        return key != null && userManager.isSystemAdmin(key);
    }

    /**
     * Create error response
     */
    private Map<String, Object> limiterSnapshot() {
        RateLimitSnapshot snapshot = rateLimiter.snapshot(LIMITER_SNAPSHOT_BUCKETS);
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("repoLimitPerHour", snapshot.getRepoLimit());
        map.put("projectLimitPerHour", snapshot.getProjectLimit());
        map.put("trackedRepoBuckets", snapshot.getTrackedRepoBuckets());
        map.put("trackedProjectBuckets", snapshot.getTrackedProjectBuckets());
        map.put("capturedAt", snapshot.getCapturedAt());
        map.put("topRepoBuckets", bucketStatesToList(snapshot.getTopRepoBuckets()));
        map.put("topProjectBuckets", bucketStatesToList(snapshot.getTopProjectBuckets()));
        return map;
    }

    private List<Map<String, Object>> bucketStatesToList(Map<String, RateLimitSnapshot.BucketState> buckets) {
        if (buckets == null || buckets.isEmpty()) {
            return Collections.emptyList();
        }
        List<Map<String, Object>> list = new ArrayList<>(buckets.size());
        buckets.forEach((scope, state) -> {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("scope", scope);
            map.put("consumed", state.getConsumed());
            map.put("limit", state.getLimit());
            map.put("remaining", state.getRemaining());
            map.put("resetInMs", state.getResetInMs());
            map.put("updatedAt", state.getUpdatedAt());
            map.put("throttledCount", state.getThrottledCount());
            map.put("lastThrottleAt", state.getLastThrottleAt());
            map.put("averageRetryAfterMs", state.getAverageRetryAfterMs());
            list.add(map);
        });
        return list;
    }

    private List<Map<String, Object>> overridesToList(List<OverrideRecord> overrides) {
        if (overrides == null || overrides.isEmpty()) {
            return Collections.emptyList();
        }
        List<Map<String, Object>> list = new ArrayList<>(overrides.size());
        for (OverrideRecord override : overrides) {
            list.add(overrideToMap(override));
        }
        return list;
    }

    private Map<String, Object> overrideToMap(OverrideRecord record) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", record.getId());
        map.put("scope", record.getScope().name().toLowerCase(Locale.ROOT));
        map.put("identifier", record.getIdentifier());
        map.put("limitPerHour", record.getLimitPerHour());
        map.put("createdAt", record.getCreatedAt());
        map.put("expiresAt", record.getExpiresAt());
        map.put("createdBy", record.getCreatedBy());
        map.put("createdByDisplayName", record.getCreatedByDisplayName());
        map.put("reason", record.getReason());
        return map;
    }

    private List<Map<String, Object>> incidentsToList(List<ThrottleIncident> incidents) {
        if (incidents == null || incidents.isEmpty()) {
            return Collections.emptyList();
        }
        List<Map<String, Object>> list = new ArrayList<>(incidents.size());
        for (ThrottleIncident incident : incidents) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("scope", incident.getScope().name().toLowerCase(Locale.ROOT));
            map.put("identifier", incident.getIdentifier());
            map.put("projectKey", incident.getProjectKey());
            map.put("repositorySlug", incident.getRepositorySlug());
            map.put("occurredAt", incident.getOccurredAt());
            map.put("limitPerHour", incident.getLimitPerHour());
            map.put("retryAfterMs", incident.getRetryAfterMs());
            map.put("reason", incident.getReason());
            list.add(map);
        }
        return list;
    }

    private GuardrailsRateLimitScope parseOverrideScope(Object raw) {
        String scope = stringValue(raw);
        if (scope == null) {
            throw new IllegalArgumentException("scope is required");
        }
        switch (scope.trim().toLowerCase(Locale.ROOT)) {
            case "global":
                return GuardrailsRateLimitScope.GLOBAL;
            case "project":
                return GuardrailsRateLimitScope.PROJECT;
            case "repo":
            case "repository":
                return GuardrailsRateLimitScope.REPOSITORY;
            default:
                throw new IllegalArgumentException("Unsupported scope value: " + scope);
        }
    }

    private String normalizeOverrideIdentifier(GuardrailsRateLimitScope scope, Object identifierValue) {
        if (scope == GuardrailsRateLimitScope.GLOBAL) {
            return null;
        }
        String identifier = stringValue(identifierValue);
        if (identifier == null || identifier.isEmpty()) {
            throw new IllegalArgumentException("identifier is required for " + scope.name().toLowerCase(Locale.ROOT));
        }
        if (scope == GuardrailsRateLimitScope.PROJECT) {
            String upper = identifier.toUpperCase(Locale.ROOT);
            if (!PROJECT_KEY_PATTERN.matcher(upper).matches()) {
                throw new IllegalArgumentException("Invalid project key: " + identifier);
            }
            return upper;
        }
        if (!REPOSITORY_SLUG_PATTERN.matcher(identifier).matches()) {
            throw new IllegalArgumentException("Invalid repository slug: " + identifier);
        }
        return identifier;
    }

    private int parsePositiveInt(Object raw, String field) {
        if (raw instanceof Number) {
            return Math.max(0, ((Number) raw).intValue());
        }
        if (raw instanceof String) {
            try {
                return Math.max(0, Integer.parseInt(((String) raw).trim()));
            } catch (NumberFormatException ignored) {
            }
        }
        throw new IllegalArgumentException(field + " must be a number");
    }

    private long resolveOverrideExpiry(Map<String, Object> payload) {
        long now = System.currentTimeMillis();
        Long duration = toLong(payload.get("durationMinutes"));
        if (duration != null && duration > 0) {
            long clamped = Math.min(duration, MAX_OVERRIDE_DURATION_MINUTES);
            return now + TimeUnit.MINUTES.toMillis(clamped);
        }
        Long explicitExpiry = toLong(payload.get("expiresAt"));
        if (explicitExpiry != null && explicitExpiry > now) {
            return explicitExpiry;
        }
        return 0L;
    }

    private Long toLong(Object raw) {
        if (raw instanceof Number) {
            return ((Number) raw).longValue();
        }
        if (raw instanceof String) {
            try {
                return Long.parseLong(((String) raw).trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private Map<String, Object> error(String message) {
        return error(message, null);
    }

    private Map<String, Object> error(String message, Map<String, String> details) {
        Map<String, Object> error = new HashMap<>();
        error.put("error", message);
        if (details != null && !details.isEmpty()) {
            error.put("details", details);
        }
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

    private Map<String, Object> normalizeConfigPayload(Map<String, Object> rawConfig) {
        Map<String, Object> normalized = new HashMap<>();
        if (rawConfig == null || rawConfig.isEmpty()) {
            return normalized;
        }

        Set<String> allowedKeys = new HashSet<>(configService.getDefaultConfiguration().keySet());
        allowedKeys.add("apiDelayMs");

        rawConfig.forEach((key, value) -> {
            if (key == null) {
                return;
            }
            if ("apiDelay".equals(key)) {
                normalized.put("apiDelayMs", value);
                return;
            }
            if ("scopeMode".equals(key)) {
                return;
            }
            if (allowedKeys.contains(key)) {
                normalized.put(key, value);
            }
        });
        return normalized;
    }

    private String stringValue(Object value) {
        if (value == null) {
            return null;
        }
        String str = value.toString().trim();
        return str.isEmpty() ? null : str;
    }

    private Boolean parseBoolean(Object value) {
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        if (value instanceof String) {
            String str = ((String) value).trim().toLowerCase(Locale.ENGLISH);
            if ("true".equals(str) || "false".equals(str)) {
                return Boolean.parseBoolean(str);
            }
        }
        if (value instanceof Number) {
            return ((Number) value).intValue() != 0;
        }
        return null;
    }

    private Response enforceRateLimit(HttpServletRequest request,
                                      UserProfile profile,
                                      String actionKey,
                                      String actionDescription,
                                      int limit,
                                      long windowMs) {
        String identity = resolveIdentity(request, profile);
        if (!RATE_LIMITER.tryAcquire(identity + ":" + actionKey, limit, windowMs)) {
            long seconds = Math.max(1, TimeUnit.MILLISECONDS.toSeconds(windowMs));
            String message = String.format("Too many %s. Please wait up to %d seconds and try again.",
                    actionDescription,
                    seconds);
            return Response.status(HTTP_TOO_MANY_REQUESTS)
                    .entity(error(message))
                    .build();
        }
        return null;
    }

    private String resolveIdentity(HttpServletRequest request, UserProfile profile) {
        if (profile != null && profile.getUserKey() != null) {
            return profile.getUserKey().getStringValue();
        }
        if (request != null && request.getRemoteAddr() != null) {
            return request.getRemoteAddr();
        }
        return "anonymous";
    }

    private static final class RateLimiter {
        private final ConcurrentMap<String, Window> windows = new ConcurrentHashMap<>();

        boolean tryAcquire(String key, int limit, long windowMs) {
            long now = System.currentTimeMillis();
            Window window = windows.computeIfAbsent(key, k -> new Window(now));
            synchronized (window) {
                if (now - window.windowStart >= windowMs) {
                    window.windowStart = now;
                    window.count.set(0);
                }
                if (window.count.incrementAndGet() > limit) {
                    return false;
                }
                return true;
            }
        }

        private static final class Window {
            volatile long windowStart;
            final AtomicInteger count = new AtomicInteger();

            Window(long windowStart) {
                this.windowStart = windowStart;
            }
        }
    }
}
