package com.teknolojikpanda.bitbucket.aireviewer.service;

import com.atlassian.activeobjects.external.ActiveObjects;
import com.atlassian.bitbucket.project.NoSuchProjectException;
import com.atlassian.bitbucket.project.Project;
import com.atlassian.bitbucket.project.ProjectService;
import com.atlassian.bitbucket.project.ProjectType;
import com.atlassian.bitbucket.repository.Repository;
import com.atlassian.bitbucket.repository.RepositoryService;
import com.atlassian.bitbucket.util.Page;
import com.atlassian.bitbucket.util.PageRequest;
import com.atlassian.bitbucket.util.PageRequestImpl;
import com.atlassian.bitbucket.user.ApplicationUser;
import com.atlassian.bitbucket.user.UserService;
import com.atlassian.plugin.spring.scanner.annotation.export.ExportAsService;
import com.atlassian.plugin.spring.scanner.annotation.imports.ComponentImport;
import com.teknolojikpanda.bitbucket.aicode.model.ReviewProfilePreset;
import com.teknolojikpanda.bitbucket.aireviewer.ao.AIReviewConfiguration;
import com.teknolojikpanda.bitbucket.aireviewer.ao.AIReviewRepoConfiguration;
import com.teknolojikpanda.bitbucket.aireviewer.util.HttpClientUtil;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.java.ao.DBParam;
import net.java.ao.Query;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Named;
import java.math.BigDecimal;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.regex.Pattern;

/**
 * Implementation of AIReviewerConfigService using Active Objects for persistence.
 */
@Named
@ExportAsService(AIReviewerConfigService.class)
public class AIReviewerConfigServiceImpl implements AIReviewerConfigService {

    private static final Logger log = LoggerFactory.getLogger(AIReviewerConfigServiceImpl.class);

    private final ActiveObjects ao;
    private final ProjectService projectService;
    private final RepositoryService repositoryService;
    private final UserService userService;
    private final HttpClientUtil httpClientUtil;

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<Map<String, Object>>() {};

    private static final String KEY_PARALLEL_THREADS = "parallelThreads";
    private static final String KEY_MAX_PARALLEL_CHUNKS = "maxParallelChunks";

    private static final Set<String> INTEGER_KEYS = Collections.unmodifiableSet(new LinkedHashSet<>(Arrays.asList(
            "maxCharsPerChunk",
            "maxFilesPerChunk",
            "maxChunks",
            KEY_PARALLEL_THREADS,
            KEY_MAX_PARALLEL_CHUNKS,
            "maxConcurrentReviews",
            "maxQueuedReviews",
            "maxQueuedPerRepo",
            "maxQueuedPerProject",
            "repoRateLimitPerHour",
            "projectRateLimitPerHour",
            "priorityRateLimitSnoozeMinutes",
            "priorityRepoRateLimitPerHour",
            "priorityProjectRateLimitPerHour",
            "repoRateLimitAlertPercent",
            "projectRateLimitAlertPercent",
            "connectTimeout",
            "readTimeout",
            "ollamaTimeout",
            "maxIssuesPerFile",
            "maxIssueComments",
            "maxDiffSize",
            "maxRetries",
            "baseRetryDelay",
            "apiDelayMs"
    )));

    private static final Set<String> BOOLEAN_KEYS = Collections.unmodifiableSet(new LinkedHashSet<>(Arrays.asList(
            "enabled",
            "reviewDraftPRs",
            "skipGeneratedFiles",
            "skipTests",
            "autoApprove",
            "workerDegradationEnabled"
    )));

    private static final Set<String> SUPPORTED_KEYS;
    private static final Set<String> DERIVED_KEYS = Collections.unmodifiableSet(new LinkedHashSet<>(Arrays.asList(
            "aiReviewerUserDisplayName"
    )));

    // Default configuration values
    private static final String DEFAULT_OLLAMA_URL = "http://0.0.0.0:11434";
    private static final String DEFAULT_OLLAMA_MODEL = "qwen3-coder:30b";
    private static final String DEFAULT_FALLBACK_MODEL = "qwen3-coder:7b";
    private static final int DEFAULT_MAX_CHARS_PER_CHUNK = 60000;
    private static final int DEFAULT_MAX_FILES_PER_CHUNK = 3;
    private static final int DEFAULT_MAX_CHUNKS = 20;
    private static final int DEFAULT_PARALLEL_THREADS = 4;
    private static final int DEFAULT_MAX_CONCURRENT_REVIEWS = 2;
    private static final int DEFAULT_MAX_QUEUED_REVIEWS = 25;
    private static final int DEFAULT_MAX_QUEUED_PER_REPO = 5;
    private static final int DEFAULT_MAX_QUEUED_PER_PROJECT = 15;
    private static final int DEFAULT_REPO_RATE_LIMIT_PER_HOUR = 12;
    private static final int DEFAULT_PROJECT_RATE_LIMIT_PER_HOUR = 60;
    private static final int DEFAULT_PRIORITY_RATE_LIMIT_SNOOZE_MINUTES = 30;
    private static final int DEFAULT_PRIORITY_REPO_RATE_LIMIT_PER_HOUR = 24;
    private static final int DEFAULT_PRIORITY_PROJECT_RATE_LIMIT_PER_HOUR = 120;
    private static final int DEFAULT_CONNECT_TIMEOUT = 10000;
    private static final int DEFAULT_READ_TIMEOUT = 30000;
    private static final int DEFAULT_OLLAMA_TIMEOUT = 300000;
    private static final int DEFAULT_MAX_ISSUES_PER_FILE = 50;
    private static final int DEFAULT_MAX_ISSUE_COMMENTS = 30;
    private static final int DEFAULT_MAX_DIFF_SIZE = 10000000;
    private static final int DEFAULT_MAX_RETRIES = 3;
    private static final int DEFAULT_BASE_RETRY_DELAY = 1000;
    private static final int DEFAULT_API_DELAY = 100;
    private static final String DEFAULT_MIN_SEVERITY = "medium";
    private static final String DEFAULT_REQUIRE_APPROVAL_FOR = "critical,high";
    private static final String DEFAULT_REVIEW_EXTENSIONS = "java,groovy,js,ts,tsx,jsx,py,go,rs,cpp,c,cs,php,rb,kt,swift,scala";
    private static final String DEFAULT_IGNORE_PATTERNS = "*.min.js,*.generated.*,package-lock.json,yarn.lock,*.map";
    private static final String DEFAULT_IGNORE_PATHS = "node_modules/,vendor/,build/,dist/,.git/";
    private static final String DEFAULT_REVIEW_PROFILE_KEY = ReviewProfilePreset.BALANCED.getKey();
    private static final String DEFAULT_SCOPE_MODE = ScopeMode.ALL.toConfigValue();
    private static final boolean DEFAULT_ENABLED = true;
    private static final boolean DEFAULT_REVIEW_DRAFT_PRS = false;
    private static final boolean DEFAULT_SKIP_GENERATED = true;
    private static final boolean DEFAULT_SKIP_TESTS = false;
    private static final boolean DEFAULT_AUTO_APPROVE = false;
    private static final boolean DEFAULT_WORKER_DEGRADATION_ENABLED = true;
    private static final String DEFAULT_PRIORITY_PROJECTS = "";
    private static final String DEFAULT_PRIORITY_REPOSITORIES = "";
    private static final int DEFAULT_REPO_ALERT_PERCENT = 80;
    private static final int DEFAULT_PROJECT_ALERT_PERCENT = 80;
    private static final String DEFAULT_REPO_ALERT_OVERRIDES = "";
    private static final String DEFAULT_PROJECT_ALERT_OVERRIDES = "";
    private static final Pattern PRIORITY_PROJECT_PATTERN = Pattern.compile("^[A-Za-z0-9_\\-]+$");
    private static final Pattern PRIORITY_REPO_PATTERN = Pattern.compile("^[A-Za-z0-9_\\-]+/[A-Za-z0-9._\\-]+$");
    private static final Pattern REPO_SLUG_PATTERN = Pattern.compile("^[A-Za-z0-9._\\-]+$");

    static {
        LinkedHashSet<String> keys = new LinkedHashSet<>(Arrays.asList(
                "ollamaUrl",
                "ollamaModel",
                "fallbackModel",
                "maxCharsPerChunk",
                "maxFilesPerChunk",
                "maxChunks",
                KEY_PARALLEL_THREADS,
                KEY_MAX_PARALLEL_CHUNKS,
                "maxConcurrentReviews",
                "maxQueuedReviews",
                "maxQueuedPerRepo",
                "maxQueuedPerProject",
                "repoRateLimitPerHour",
                "projectRateLimitPerHour",
                "priorityProjects",
                "priorityRepositories",
                "priorityRateLimitSnoozeMinutes",
                "priorityRepoRateLimitPerHour",
                "priorityProjectRateLimitPerHour",
                "repoRateLimitAlertPercent",
                "projectRateLimitAlertPercent",
                "repoRateLimitAlertOverrides",
                "projectRateLimitAlertOverrides",
                "connectTimeout",
                "readTimeout",
                "ollamaTimeout",
                "maxIssuesPerFile",
                "maxIssueComments",
                "maxDiffSize",
                "maxRetries",
                "baseRetryDelay",
                "apiDelayMs",
                "minSeverity",
                "requireApprovalFor",
                "reviewExtensions",
                "ignorePatterns",
                "ignorePaths",
                "reviewProfile",
                "enabled",
                "reviewDraftPRs",
                "skipGeneratedFiles",
                "skipTests",
                "autoApprove",
                "workerDegradationEnabled",
                "aiReviewerUser",
                "scopeMode"
        ));
        SUPPORTED_KEYS = Collections.unmodifiableSet(keys);
    }

    @Inject
    public AIReviewerConfigServiceImpl(
            @ComponentImport ActiveObjects ao,
            @ComponentImport ProjectService projectService,
            @ComponentImport RepositoryService repositoryService,
            @ComponentImport UserService userService) {
        this(ao, projectService, repositoryService, userService, new HttpClientUtil());
    }

    AIReviewerConfigServiceImpl(ActiveObjects ao) {
        this(ao, null, null, null, new HttpClientUtil());
    }

    AIReviewerConfigServiceImpl(ActiveObjects ao,
                                ProjectService projectService,
                                RepositoryService repositoryService,
                                UserService userService,
                                HttpClientUtil httpClientUtil) {
        this.ao = Objects.requireNonNull(ao, "activeObjects cannot be null");
        this.projectService = projectService;
        this.repositoryService = repositoryService;
        this.userService = userService;
        this.httpClientUtil = httpClientUtil != null ? httpClientUtil : new HttpClientUtil();
    }

    @Nonnull
    @Override
    public AIReviewConfiguration getGlobalConfiguration() {
        return ao.executeInTransaction(() -> {
            AIReviewConfiguration[] configs = ao.find(AIReviewConfiguration.class);

            if (configs.length == 0) {
                log.info("No configuration found, creating default configuration");
                return createDefaultConfiguration();
            }

            AIReviewConfiguration config = configs[0];
            long now = System.currentTimeMillis();
            if (applyMissingDefaults(config, now)) {
                log.info("Detected incomplete configuration, backfilled missing defaults");
                config.setModifiedDate(now);
                if (config.getCreatedDate() == 0L) {
                    config.setCreatedDate(now);
                }
                config.save();
            }
            return config;
        });
    }

    @Nonnull
    @Override
    public AIReviewConfiguration updateConfiguration(@Nonnull Map<String, Object> configMap) {
        Objects.requireNonNull(configMap, "configMap cannot be null");
        validateConfiguration(configMap);

        return ao.executeInTransaction(() -> {
            AIReviewConfiguration config = getOrCreateConfiguration();

            // Update all fields from the map using safe setters
            updateConfigurationFields(config, configMap);

            config.save();
            log.info("Configuration updated successfully");
            return config;
        });
    }

    @Nonnull
    @Override
    public Map<String, Object> getConfigurationAsMap() {
        AIReviewConfiguration config = getGlobalConfiguration();
        return convertToMap(config);
    }

    @Override
    public void validateConfiguration(@Nonnull Map<String, Object> configMap) {
        Objects.requireNonNull(configMap, "configMap cannot be null");
        normalizeParallelChunkKeys(configMap);

        Map<String, String> errors = new LinkedHashMap<>();

        configMap.keySet().stream()
                .filter(Objects::nonNull)
                .map(Object::toString)
                .filter(key -> !SUPPORTED_KEYS.contains(key) && !DERIVED_KEYS.contains(key))
                .forEach(key -> errors.putIfAbsent(key, "Unsupported configuration key '" + key + "'"));

        validateString(configMap, "ollamaUrl", true, 2048, errors, value -> {
            try {
                validateUrl(value);
            } catch (IllegalArgumentException e) {
                errors.put("ollamaUrl", e.getMessage());
            }
        });
        validateString(configMap, "ollamaModel", true, 512, errors, null);
        validateString(configMap, "fallbackModel", true, 512, errors, null);

        String ollamaModel = trimToNull(configMap.get("ollamaModel"));
        String fallbackModel = trimToNull(configMap.get("fallbackModel"));
        if (ollamaModel != null && fallbackModel != null && ollamaModel.equalsIgnoreCase(fallbackModel)) {
            errors.put("fallbackModel", "Fallback model must differ from the primary Ollama model");
        }

        validateIntegerRange(configMap, "maxCharsPerChunk", 10_000, 100_000, errors);
        validateIntegerRange(configMap, "maxFilesPerChunk", 1, 10, errors);
        validateIntegerRange(configMap, "maxChunks", 1, 50, errors);
        validateIntegerRange(configMap, "parallelThreads", 1, 16, errors);
        validateIntegerRange(configMap, KEY_MAX_PARALLEL_CHUNKS, 1, 16, errors);
        validateIntegerRange(configMap, "maxConcurrentReviews", 1, 32, errors);
        validateIntegerRange(configMap, "maxQueuedReviews", 0, 500, errors);
        validateIntegerRange(configMap, "maxQueuedPerRepo", 0, 200, errors);
        validateIntegerRange(configMap, "maxQueuedPerProject", 0, 500, errors);
        validateIntegerRange(configMap, "repoRateLimitPerHour", 0, 1000, errors);
        validateIntegerRange(configMap, "projectRateLimitPerHour", 0, 2000, errors);
        validateIntegerRange(configMap, "maxIssuesPerFile", 1, 100, errors);
        validateIntegerRange(configMap, "maxIssueComments", 1, 100, errors);
        validateIntegerRange(configMap, "maxRetries", 0, 10, errors);
        validateIntegerRange(configMap, "baseRetryDelay", 100, 60_000, errors);
        validateIntegerRange(configMap, "ollamaTimeout", 5_000, 600_000, errors);
        validateIntegerRange(configMap, "connectTimeout", 1_000, 120_000, errors);
        validateString(configMap, "aiReviewerUser", false, 255, errors, null);
        validateString(configMap, "priorityProjects", false, 2000, errors,
                value -> validatePriorityScopeList("priorityProjects", value, true, errors));
        validateString(configMap, "priorityRepositories", false, 4000, errors,
                value -> validatePriorityScopeList("priorityRepositories", value, false, errors));
        validateIntegerRange(configMap, "priorityRateLimitSnoozeMinutes", 1, 1440, errors);
        validateIntegerRange(configMap, "priorityRepoRateLimitPerHour", 0, 2000, errors);
        validateIntegerRange(configMap, "priorityProjectRateLimitPerHour", 0, 4000, errors);
        validateIntegerRange(configMap, "repoRateLimitAlertPercent", 1, 100, errors);
        validateIntegerRange(configMap, "projectRateLimitAlertPercent", 1, 100, errors);
        validateAlertOverrides(configMap, "repoRateLimitAlertOverrides", false, errors);
        validateAlertOverrides(configMap, "projectRateLimitAlertOverrides", true, errors);

        String minSeverity = trimToNull(configMap.get("minSeverity"));
        if (minSeverity != null && !isValidSeverity(minSeverity)) {
            errors.put("minSeverity", "Invalid severity '" + minSeverity + "'. Allowed values: low, medium, high, critical");
        }

        String profileKey = trimToNull(configMap.get("reviewProfile"));
        if (profileKey != null && ReviewProfilePreset.fromKey(profileKey).isEmpty()) {
            errors.put("reviewProfile", "Unknown profile preset '" + profileKey + "'");
        }

        String requireApproval = trimToNull(configMap.get("requireApprovalFor"));
        if (requireApproval != null) {
            List<String> invalid = Arrays.stream(requireApproval.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .filter(s -> !isValidSeverity(s))
                    .collect(Collectors.toList());
            if (!invalid.isEmpty()) {
                errors.put("requireApprovalFor", "Invalid severity values: " + String.join(", ", invalid));
            }
        }

        configMap.forEach((key, value) -> {
            if (key == null || value == null) {
                return;
            }
            String lower = key.toLowerCase(Locale.ROOT);
            if (!lower.startsWith("prompt")) {
                return;
            }
            if (!(value instanceof String)) {
                errors.put(key, "Prompt values must be strings");
                return;
            }
            String text = ((String) value).trim();
            if (text.isEmpty()) {
                errors.put(key, "Prompt overrides cannot be empty");
            } else if (text.length() > 10_000) {
                errors.put(key, "Prompt overrides must be 10,000 characters or fewer");
            }
        });

        String reviewerUser = trimToNull(configMap.get("aiReviewerUser"));
        if (reviewerUser != null && userService != null) {
            ApplicationUser user = userService.getUserBySlug(reviewerUser);
            if (user == null || !userService.isUserActive(user)) {
                errors.put("aiReviewerUser", "User '" + reviewerUser + "' not found or inactive");
            }
        }

        if (!errors.isEmpty()) {
            log.warn("Configuration validation errors: {}", errors);
            throw new ConfigurationValidationException(errors);
        }
    }

    private void validateString(Map<String, Object> map,
                                String key,
                                boolean enforceNonBlank,
                                int maxLength,
                                Map<String, String> errors,
                                Consumer<String> additionalValidation) {
        Object raw = map.get(key);
        if (raw == null) {
            return;
        }
        if (!(raw instanceof String)) {
            errors.put(key, "Expected string value but received " + raw.getClass().getSimpleName());
            return;
        }
        String value = ((String) raw).trim();
        if (value.isEmpty()) {
            if (enforceNonBlank) {
                errors.put(key, "Value cannot be empty");
            }
            return;
        }
        if (value.length() > maxLength) {
            errors.put(key, "Value must be " + maxLength + " characters or fewer");
            return;
        }
        if (additionalValidation != null) {
            additionalValidation.accept(value);
        }
    }

    @Nonnull
    @Override
    public AIReviewConfiguration resetToDefaults() {
        return ao.executeInTransaction(() -> {
            // Delete existing configuration
            AIReviewConfiguration[] configs = ao.find(AIReviewConfiguration.class);
            for (AIReviewConfiguration config : configs) {
                ao.delete(config);
            }

            // Create new default configuration
            log.info("Resetting configuration to defaults");
            return createDefaultConfiguration();
        });
    }

    @Override
    public boolean testOllamaConnection(@Nonnull String ollamaUrl) {
        Objects.requireNonNull(ollamaUrl, "ollamaUrl cannot be null");

        // First validate URL format
        try {
            validateUrl(ollamaUrl);
        } catch (IllegalArgumentException e) {
            log.error("Ollama URL validation failed: {}", e.getMessage());
            return false;
        }

        // Perform actual HTTP connection test using HttpClientUtil
        log.info("Testing Ollama connection to: {}", ollamaUrl);
        boolean connected = httpClientUtil.testConnection(ollamaUrl);

        if (connected) {
            log.info("✅ Ollama connection test successful: {}", ollamaUrl);
        } else {
            log.warn("❌ Ollama connection test failed: {}", ollamaUrl);
        }

        return connected;
    }

    @Nonnull
    @Override
    public Map<String, Object> getDefaultConfiguration() {
        Map<String, Object> defaults = new HashMap<>();
        defaults.put("ollamaUrl", DEFAULT_OLLAMA_URL);
        defaults.put("ollamaModel", DEFAULT_OLLAMA_MODEL);
        defaults.put("fallbackModel", DEFAULT_FALLBACK_MODEL);
        defaults.put("maxCharsPerChunk", DEFAULT_MAX_CHARS_PER_CHUNK);
        defaults.put("maxFilesPerChunk", DEFAULT_MAX_FILES_PER_CHUNK);
        defaults.put("maxChunks", DEFAULT_MAX_CHUNKS);
        defaults.put(KEY_PARALLEL_THREADS, DEFAULT_PARALLEL_THREADS);
        defaults.put(KEY_MAX_PARALLEL_CHUNKS, DEFAULT_PARALLEL_THREADS);
        defaults.put("connectTimeout", DEFAULT_CONNECT_TIMEOUT);
        defaults.put("readTimeout", DEFAULT_READ_TIMEOUT);
        defaults.put("ollamaTimeout", DEFAULT_OLLAMA_TIMEOUT);
        defaults.put("maxIssuesPerFile", DEFAULT_MAX_ISSUES_PER_FILE);
        defaults.put("maxIssueComments", DEFAULT_MAX_ISSUE_COMMENTS);
        defaults.put("maxDiffSize", DEFAULT_MAX_DIFF_SIZE);
        defaults.put("maxRetries", DEFAULT_MAX_RETRIES);
        defaults.put("baseRetryDelay", DEFAULT_BASE_RETRY_DELAY);
        defaults.put("apiDelayMs", DEFAULT_API_DELAY);
        defaults.put("minSeverity", DEFAULT_MIN_SEVERITY);
        defaults.put("requireApprovalFor", DEFAULT_REQUIRE_APPROVAL_FOR);
        defaults.put("reviewExtensions", DEFAULT_REVIEW_EXTENSIONS);
        defaults.put("ignorePatterns", DEFAULT_IGNORE_PATTERNS);
        defaults.put("ignorePaths", DEFAULT_IGNORE_PATHS);
        defaults.put("reviewProfile", DEFAULT_REVIEW_PROFILE_KEY);
        defaults.put("enabled", DEFAULT_ENABLED);
        defaults.put("reviewDraftPRs", DEFAULT_REVIEW_DRAFT_PRS);
        defaults.put("skipGeneratedFiles", DEFAULT_SKIP_GENERATED);
        defaults.put("skipTests", DEFAULT_SKIP_TESTS);
        defaults.put("autoApprove", DEFAULT_AUTO_APPROVE);
        defaults.put("workerDegradationEnabled", DEFAULT_WORKER_DEGRADATION_ENABLED);
        defaults.put("aiReviewerUser", null);
        defaults.put("priorityProjects", DEFAULT_PRIORITY_PROJECTS);
        defaults.put("priorityRepositories", DEFAULT_PRIORITY_REPOSITORIES);
        defaults.put("priorityRateLimitSnoozeMinutes", DEFAULT_PRIORITY_RATE_LIMIT_SNOOZE_MINUTES);
        defaults.put("priorityRepoRateLimitPerHour", DEFAULT_PRIORITY_REPO_RATE_LIMIT_PER_HOUR);
        defaults.put("priorityProjectRateLimitPerHour", DEFAULT_PRIORITY_PROJECT_RATE_LIMIT_PER_HOUR);
        defaults.put("repoRateLimitAlertPercent", DEFAULT_REPO_ALERT_PERCENT);
        defaults.put("projectRateLimitAlertPercent", DEFAULT_PROJECT_ALERT_PERCENT);
        defaults.put("repoRateLimitAlertOverrides", DEFAULT_REPO_ALERT_OVERRIDES);
        defaults.put("projectRateLimitAlertOverrides", DEFAULT_PROJECT_ALERT_OVERRIDES);
        return Collections.unmodifiableMap(defaults);
    }

    @Nonnull
    @Override
    public List<Map<String, Object>> listRepositoryConfigurations() {
        return ao.executeInTransaction(() -> {
            AIReviewRepoConfiguration[] entries = ao.find(AIReviewRepoConfiguration.class, Query.select());
            List<Map<String, Object>> results = new ArrayList<>(entries.length);
        for (AIReviewRepoConfiguration entry : entries) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("projectKey", entry.getProjectKey());
            map.put("repositorySlug", entry.getRepositorySlug());
            map.put("overrides", parseConfigurationJson(entry.getConfigurationJson()));
            map.put("modifiedDate", entry.getModifiedDate());
            map.put("modifiedBy", entry.getModifiedBy());
            map.put("inheritGlobal", entry.isInheritGlobal());
            results.add(map);
        }
            return results;
        });
    }

    @Nonnull
    @Override
    public Map<String, Object> getRepositoryConfiguration(@Nonnull String projectKey,
                                                          @Nonnull String repositorySlug) {
        Objects.requireNonNull(projectKey, "projectKey");
        Objects.requireNonNull(repositorySlug, "repositorySlug");

        Map<String, Object> defaults = new LinkedHashMap<>(getDefaultConfiguration());
        Map<String, Object> global = new LinkedHashMap<>(getConfigurationAsMap());
        Map<String, Object> overrides = loadRepositoryOverrides(projectKey, repositorySlug);
        Map<String, Object> effective = new LinkedHashMap<>(global);
        overrides.forEach(effective::put);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("projectKey", projectKey);
        response.put("repositorySlug", repositorySlug);
        response.put("defaults", defaults);
        response.put("global", global);
        response.put("overrides", new LinkedHashMap<>(overrides));
        response.put("effective", effective);
        AIReviewRepoConfiguration entity = findRepoConfiguration(projectKey, repositorySlug);
        response.put("inheritGlobal", entity == null || entity.isInheritGlobal());
        return response;
    }

    @Nonnull
    @Override
    public Map<String, Object> getEffectiveConfiguration(@Nonnull String projectKey,
                                                         @Nonnull String repositorySlug) {
        Objects.requireNonNull(projectKey, "projectKey");
        Objects.requireNonNull(repositorySlug, "repositorySlug");

        Map<String, Object> global = new LinkedHashMap<>(getConfigurationAsMap());
        Map<String, Object> overrides = loadRepositoryOverrides(projectKey, repositorySlug);
        overrides.forEach(global::put);
        return Collections.unmodifiableMap(global);
    }

    @Override
    public void updateRepositoryConfiguration(@Nonnull String projectKey,
                                              @Nonnull String repositorySlug,
                                              @Nonnull Map<String, Object> overrides,
                                              String updatedBy) {
        Objects.requireNonNull(projectKey, "projectKey");
        Objects.requireNonNull(repositorySlug, "repositorySlug");
        Objects.requireNonNull(overrides, "overrides");

        Map<String, Object> sanitized = normalizeOverrides(overrides);
        Map<String, Object> globalConfig = new LinkedHashMap<>(getConfigurationAsMap());
        Map<String, Object> overrideDiff = stripMatchingGlobalValues(sanitized, globalConfig);
        Map<String, Object> effective = new LinkedHashMap<>(globalConfig);
        overrideDiff.forEach(effective::put);
        validateConfiguration(effective);

        ao.executeInTransaction(() -> {
            AIReviewRepoConfiguration existing = findRepoConfiguration(projectKey, repositorySlug);
            long now = System.currentTimeMillis();

            if (overrideDiff.isEmpty()) {
                if (existing == null) {
                    existing = ao.create(AIReviewRepoConfiguration.class,
                            new DBParam("PROJECT_KEY", projectKey),
                            new DBParam("REPOSITORY_SLUG", repositorySlug));
                    existing.setCreatedDate(now);
                }
                existing.setConfigurationJson("{}");
                existing.setInheritGlobal(true);
                existing.setModifiedDate(now);
                if (updatedBy != null) {
                    existing.setModifiedBy(updatedBy);
                }
                existing.save();
                return null;
            }

            if (existing == null) {
                existing = ao.create(AIReviewRepoConfiguration.class,
                        new DBParam("PROJECT_KEY", projectKey),
                        new DBParam("REPOSITORY_SLUG", repositorySlug));
                existing.setCreatedDate(now);
            }
            existing.setConfigurationJson(writeConfigurationJson(overrideDiff));
            existing.setInheritGlobal(false);
            existing.setModifiedDate(now);
            if (updatedBy != null) {
                existing.setModifiedBy(updatedBy);
            }
            existing.save();
            return null;
        });
    }

    @Override
    public void clearRepositoryConfiguration(@Nonnull String projectKey, @Nonnull String repositorySlug) {
        Objects.requireNonNull(projectKey, "projectKey");
        Objects.requireNonNull(repositorySlug, "repositorySlug");

        ao.executeInTransaction(() -> {
            AIReviewRepoConfiguration existing = findRepoConfiguration(projectKey, repositorySlug);
            if (existing != null) {
                ao.delete(existing);
            }
            return null;
        });
    }

    @Nonnull
    @Override
    public List<Map<String, Object>> listRepositoryCatalog() {
        return getRepositoryCatalog(0, 0).getProjects();
    }

    @Nonnull
    @Override
    public RepositoryCatalogPage getRepositoryCatalog(int start, int limit) {
        if (projectService == null || repositoryService == null) {
            log.debug("Project catalogue unavailable (projectService={}, repositoryService={})",
                    projectService != null, repositoryService != null);
            return new RepositoryCatalogPage(Collections.emptyList(), Math.max(0, start), 0, 0);
        }

        int safeStart = Math.max(0, start);
        int safeLimit = limit < 0 ? 0 : Math.min(limit, 500);

        List<Map<String, Object>> allProjects = new ArrayList<>();
        PageRequest projectRequest = new PageRequestImpl(0, 100);
        Page<Project> projectPage;

        do {
            projectPage = projectService.findAll(projectRequest);
            for (Project project : projectPage.getValues()) {
                allProjects.add(buildProjectCatalogueEntry(project));
            }
            projectRequest = projectPage.getNextPageRequest();
        } while (projectRequest != null && !projectPage.getIsLastPage());

        allProjects.sort(this::compareProjectsForCatalogue);
        int total = allProjects.size();
        int actualStart = Math.min(safeStart, total);
        int endExclusive = safeLimit > 0 ? Math.min(actualStart + safeLimit, total) : total;
        List<Map<String, Object>> slice = allProjects.subList(actualStart, endExclusive);
        return new RepositoryCatalogPage(slice, actualStart, slice.size(), total);
    }

    @Nonnull
    @Override
    public ScopeMode getScopeMode() {
        AIReviewConfiguration config = getGlobalConfiguration();
        return ScopeMode.fromString(config.getScopeMode());
    }

    @Override
    public boolean isRepositoryWithinScope(@Nonnull String projectKey, @Nonnull String repositorySlug) {
        Objects.requireNonNull(projectKey, "projectKey");
        Objects.requireNonNull(repositorySlug, "repositorySlug");

        ScopeMode mode = getScopeMode();
        if (mode == ScopeMode.ALL) {
            return true;
        }
        return ao.executeInTransaction(() -> findRepoConfiguration(projectKey, repositorySlug) != null);
    }

    @Override
    public boolean isPriorityProject(@Nullable String projectKey) {
        String normalized = canonicalProjectKey(projectKey);
        if (normalized == null) {
            return false;
        }
        AIReviewConfiguration config = getGlobalConfiguration();
        return parsePriorityProjects(config.getPriorityProjects()).contains(normalized);
    }

    @Override
    public boolean isPriorityRepository(@Nullable String projectKey, @Nullable String repositorySlug) {
        String normalized = canonicalRepoKey(projectKey, repositorySlug);
        if (normalized == null) {
            return false;
        }
        AIReviewConfiguration config = getGlobalConfiguration();
        return parsePriorityRepositories(config.getPriorityRepositories()).contains(normalized);
    }

    @Override
    public int getPriorityRateLimitSnoozeMinutes() {
        AIReviewConfiguration config = getGlobalConfiguration();
        int configured = config.getPrioritySnoozeMinutes();
        return configured > 0 ? configured : DEFAULT_PRIORITY_RATE_LIMIT_SNOOZE_MINUTES;
    }

    @Override
    public int getPriorityProjectRateLimitPerHour() {
        AIReviewConfiguration config = getGlobalConfiguration();
        int configured = config.getPriorityProjectRateLimit();
        if (configured > 0) {
            return configured;
        }
        int base = Math.max(config.getProjectRateLimitPerHour(), DEFAULT_PROJECT_RATE_LIMIT_PER_HOUR);
        return Math.max(DEFAULT_PRIORITY_PROJECT_RATE_LIMIT_PER_HOUR, base);
    }

    @Override
    public int getPriorityRepoRateLimitPerHour() {
        AIReviewConfiguration config = getGlobalConfiguration();
        int configured = config.getPriorityRepoRateLimit();
        if (configured > 0) {
            return configured;
        }
        int base = Math.max(config.getRepoRateLimitPerHour(), DEFAULT_REPO_RATE_LIMIT_PER_HOUR);
        return Math.max(DEFAULT_PRIORITY_REPO_RATE_LIMIT_PER_HOUR, base);
    }

    @Override
    public int getRepoRateLimitAlertPercent() {
        AIReviewConfiguration config = getGlobalConfiguration();
        int percent = config.getRepoAlertPercent();
        return percent > 0 ? percent : DEFAULT_REPO_ALERT_PERCENT;
    }

    @Override
    public int getProjectRateLimitAlertPercent() {
        AIReviewConfiguration config = getGlobalConfiguration();
        int percent = config.getProjectAlertPercent();
        return percent > 0 ? percent : DEFAULT_PROJECT_ALERT_PERCENT;
    }

    @Override
    public int resolveRepoRateLimitAlertPercent(@Nullable String repositorySlug) {
        AIReviewConfiguration config = getGlobalConfiguration();
        Map<String, Integer> overrides = parseRepoAlertOverrides(config.getRepoAlertOverrides());
        String slug = canonicalRepoSlug(repositorySlug);
        if (slug != null) {
            Integer override = overrides.get(slug);
            if (override != null && override > 0) {
                return override;
            }
        }
        int percent = config.getRepoAlertPercent();
        return percent > 0 ? percent : DEFAULT_REPO_ALERT_PERCENT;
    }

    @Override
    public int resolveProjectRateLimitAlertPercent(@Nullable String projectKey) {
        AIReviewConfiguration config = getGlobalConfiguration();
        Map<String, Integer> overrides = parseProjectAlertOverrides(config.getProjectAlertOverrides());
        String normalized = canonicalProjectKey(projectKey);
        if (normalized != null) {
            Integer override = overrides.get(normalized);
            if (override != null && override > 0) {
                return override;
            }
        }
        int percent = config.getProjectAlertPercent();
        return percent > 0 ? percent : DEFAULT_PROJECT_ALERT_PERCENT;
    }

    @Override
    public void synchronizeRepositoryOverrides(@Nonnull Collection<RepositoryScope> desiredRepositories,
                                               @Nonnull ScopeMode scopeMode,
                                               String updatedBy) {
        Objects.requireNonNull(desiredRepositories, "desiredRepositories");
        Objects.requireNonNull(scopeMode, "scopeMode");

        LinkedHashSet<RepositoryScope> desired = sanitizeDesiredRepositories(desiredRepositories);
        Set<String> desiredKeys = desired.stream()
                .map(this::repositoryKey)
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        if (desiredKeys.size() > 1000) {
            throw new IllegalArgumentException("Too many repository selections (max 1000)");
        }

        Map<String, Object> globalConfig = new LinkedHashMap<>(getConfigurationAsMap());
        long now = System.currentTimeMillis();

        ao.executeInTransaction(() -> {
            AIReviewRepoConfiguration[] existing = ao.find(AIReviewRepoConfiguration.class, Query.select());
            Map<String, AIReviewRepoConfiguration> existingMap = Arrays.stream(existing)
                    .collect(Collectors.toMap(
                            entry -> repositoryKey(entry.getProjectKey(), entry.getRepositorySlug()),
                            entry -> entry,
                            (left, right) -> left,
                            LinkedHashMap::new));

            for (Map.Entry<String, AIReviewRepoConfiguration> entry : existingMap.entrySet()) {
                if (!desiredKeys.contains(entry.getKey())) {
                    ao.delete(entry.getValue());
                }
            }

            for (RepositoryScope scope : desired) {
                String key = repositoryKey(scope);
                if (key == null) {
                    continue;
                }

                AIReviewRepoConfiguration entity = existingMap.get(key);
                Map<String, Object> existingOverrides = Collections.emptyMap();
                if (entity == null) {
                    entity = ao.create(AIReviewRepoConfiguration.class,
                            new DBParam("PROJECT_KEY", scope.getProjectKey()),
                            new DBParam("REPOSITORY_SLUG", scope.getRepositorySlug()));
                    entity.setCreatedDate(now);
                } else {
                    existingOverrides = parseConfigurationJson(entity.getConfigurationJson());
                }

                Map<String, Object> delta = stripMatchingGlobalValues(existingOverrides, globalConfig);
                if (delta.isEmpty()) {
                    entity.setConfigurationJson("{}");
                    entity.setInheritGlobal(true);
                } else {
                    entity.setConfigurationJson(writeConfigurationJson(delta));
                    entity.setInheritGlobal(false);
                }
                entity.setModifiedDate(now);
                if (updatedBy != null) {
                    entity.setModifiedBy(updatedBy);
                }
                entity.save();
            }

            AIReviewConfiguration configuration = getOrCreateConfiguration();
            String desiredMode = scopeMode.toConfigValue();
            if (!desiredMode.equals(configuration.getScopeMode())) {
                configuration.setScopeMode(desiredMode);
                configuration.setModifiedDate(now);
                if (updatedBy != null) {
                    configuration.setModifiedBy(updatedBy);
                }
                configuration.save();
            }
            return null;
        });
    }

    // Private helper methods

    private Map<String, Object> loadRepositoryOverrides(String projectKey, String repositorySlug) {
        return ao.executeInTransaction(() -> {
            AIReviewRepoConfiguration entity = findRepoConfiguration(projectKey, repositorySlug);
            if (entity == null) {
                return Collections.emptyMap();
            }
            return parseConfigurationJson(entity.getConfigurationJson());
        });
    }

    private AIReviewRepoConfiguration findRepoConfiguration(String projectKey, String repositorySlug) {
        AIReviewRepoConfiguration[] configs = ao.find(
                AIReviewRepoConfiguration.class,
                Query.select()
                        .where("PROJECT_KEY = ? AND REPOSITORY_SLUG = ?", projectKey, repositorySlug)
                        .limit(1));
        return configs.length == 0 ? null : configs[0];
    }

    private Map<String, Object> buildProjectCatalogueEntry(Project project) {
        Map<String, Object> projectMap = new LinkedHashMap<>();
        projectMap.put("projectId", project.getId());
        projectMap.put("projectKey", project.getKey());
        projectMap.put("projectName", project.getName());
        ProjectType type = project.getType();
        projectMap.put("projectType", type != null ? type.name() : ProjectType.NORMAL.name());
        projectMap.put("personal", type == ProjectType.PERSONAL);
        projectMap.put("public", project.isPublic());
        List<Map<String, Object>> repositories = collectRepositories(project);
        projectMap.put("repositories", repositories);
        projectMap.put("repositoryCount", repositories.size());
        return projectMap;
    }

    private List<Map<String, Object>> collectRepositories(Project project) {
        List<Map<String, Object>> repositories = new ArrayList<>();
        if (repositoryService == null) {
            return repositories;
        }
        PageRequest repoRequest = new PageRequestImpl(0, 100);
        Page<Repository> repoPage;
        do {
            String projectKey = project.getKey();
            if (projectKey == null) {
                break;
            }
            try {
                repoPage = repositoryService.findByProjectKey(projectKey, repoRequest);
            } catch (NoSuchProjectException e) {
                log.warn("Repository catalogue: project no longer exists (key={})", projectKey, e);
                break;
            }
            for (Repository repository : repoPage.getValues()) {
                repositories.add(buildRepositoryCatalogueEntry(project, repository));
            }
            repoRequest = repoPage.getNextPageRequest();
        } while (repoRequest != null && !repoPage.getIsLastPage());

        repositories.sort(Comparator.comparing(entry ->
                ((String) entry.getOrDefault("repositorySlug", "")).toLowerCase(Locale.ROOT)));
        return repositories;
    }

    private Map<String, Object> buildRepositoryCatalogueEntry(Project project, Repository repository) {
        Map<String, Object> repoMap = new LinkedHashMap<>();
        repoMap.put("repositoryId", repository.getId());
        repoMap.put("repositorySlug", repository.getSlug());
        repoMap.put("repositoryName", repository.getName());
        repoMap.put("projectKey", project.getKey());
        repoMap.put("projectId", project.getId());
        repoMap.put("state", repository.getState() != null ? repository.getState().name() : "AVAILABLE");
        return repoMap;
    }

    private int compareProjectsForCatalogue(Map<String, Object> left, Map<String, Object> right) {
        String leftType = toStringOrEmpty(left.get("projectType"));
        String rightType = toStringOrEmpty(right.get("projectType"));
        if (!leftType.equalsIgnoreCase(rightType)) {
            if ("PERSONAL".equalsIgnoreCase(leftType)) {
                return 1;
            }
            if ("PERSONAL".equalsIgnoreCase(rightType)) {
                return -1;
            }
        }
        String leftKey = toStringOrEmpty(left.get("projectKey"));
        String rightKey = toStringOrEmpty(right.get("projectKey"));
        return leftKey.compareToIgnoreCase(rightKey);
    }

    private LinkedHashSet<RepositoryScope> sanitizeDesiredRepositories(Collection<RepositoryScope> desiredRepositories) {
        LinkedHashSet<RepositoryScope> sanitized = new LinkedHashSet<>();
        if (desiredRepositories == null) {
            return sanitized;
        }
        for (RepositoryScope scope : desiredRepositories) {
            if (scope == null) {
                continue;
            }
            String projectKey = scope.getProjectKey();
            String repositorySlug = scope.getRepositorySlug();
            if (isBlank(projectKey) || isBlank(repositorySlug)) {
                continue;
            }
            sanitized.add(new RepositoryScope(projectKey.trim(), repositorySlug.trim()));
        }
        return sanitized;
    }

    private String repositoryKey(RepositoryScope scope) {
        if (scope == null) {
            return null;
        }
        return repositoryKey(scope.getProjectKey(), scope.getRepositorySlug());
    }

    private String repositoryKey(String projectKey, String repositorySlug) {
        if (isBlank(projectKey) || isBlank(repositorySlug)) {
            return null;
        }
        return projectKey.trim() + "/" + repositorySlug.trim();
    }

    private String toStringOrEmpty(Object value) {
        return value == null ? "" : value.toString();
    }

    private Map<String, Object> normalizeOverrides(Map<String, Object> overrides) {
        Map<String, Object> normalized = new LinkedHashMap<>();
        if (overrides == null || overrides.isEmpty()) {
            return normalized;
        }
        overrides.forEach((key, value) -> {
            if (key == null) {
                return;
            }
            String trimmedKey = key.trim();
            if (trimmedKey.isEmpty() || !SUPPORTED_KEYS.contains(trimmedKey)) {
                return;
            }
            Object normalizedValue = normalizeOverrideValue(trimmedKey, value);
            if (normalizedValue != null) {
                normalized.put(trimmedKey, normalizedValue);
            }
        });
        return normalized;
    }

    private Object normalizeOverrideValue(String key, Object value) {
        if (value == null) {
            return null;
        }
        if (INTEGER_KEYS.contains(key)) {
            return parseInteger(value);
        }
        if (BOOLEAN_KEYS.contains(key)) {
            return parseBoolean(value);
        }
        if (!(value instanceof String)) {
            value = String.valueOf(value);
        }
        String trimmed = ((String) value).trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private Map<String, Object> stripMatchingGlobalValues(Map<String, Object> overrides,
                                                          Map<String, Object> globalConfig) {
        Map<String, Object> delta = new LinkedHashMap<>();
        if (overrides == null || overrides.isEmpty()) {
            return delta;
        }
        overrides.forEach((key, value) -> {
            if (key == null || value == null) {
                return;
            }
            Object baseline = globalConfig != null ? globalConfig.get(key) : null;
            if (!valuesEqual(value, baseline)) {
                delta.put(key, value);
            }
        });
        return delta;
    }

    private boolean valuesEqual(Object left, Object right) {
        if (left == right) {
            return true;
        }
        if (left == null || right == null) {
            return false;
        }
        if (left instanceof Number && right instanceof Number) {
            return numbersEqual((Number) left, (Number) right);
        }
        return Objects.equals(left, right);
    }

    private boolean numbersEqual(Number left, Number right) {
        if (isFloatingPoint(left) || isFloatingPoint(right)) {
            return Double.compare(left.doubleValue(), right.doubleValue()) == 0;
        }
        return new BigDecimal(left.toString()).compareTo(new BigDecimal(right.toString())) == 0;
    }

    private boolean isFloatingPoint(Number number) {
        return number instanceof Double || number instanceof Float;
    }

    private Boolean parseBoolean(Object value) {
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        if (value instanceof Number) {
            return ((Number) value).intValue() != 0;
        }
        if (value instanceof String) {
            String trimmed = ((String) value).trim();
            if (trimmed.isEmpty()) {
                return null;
            }
            return Boolean.parseBoolean(trimmed);
        }
        return null;
    }

    private Map<String, Object> parseConfigurationJson(String json) {
        if (json == null || json.trim().isEmpty()) {
            return Collections.emptyMap();
        }
        try {
            Map<String, Object> data = OBJECT_MAPPER.readValue(json, MAP_TYPE);
            return normalizeOverrides(data);
        } catch (Exception e) {
            log.warn("Failed to parse repository configuration JSON", e);
            return Collections.emptyMap();
        }
    }

    private String writeConfigurationJson(Map<String, Object> overrides) {
        try {
            return OBJECT_MAPPER.writeValueAsString(overrides);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialise repository configuration overrides", e);
        }
    }

    private AIReviewConfiguration getOrCreateConfiguration() {
        AIReviewConfiguration[] configs = ao.find(AIReviewConfiguration.class, Query.select());

        if (configs.length == 0) {
            return createDefaultConfiguration();
        }

        return configs[0];
    }

    private AIReviewConfiguration createDefaultConfiguration() {
        AIReviewConfiguration config = ao.create(
                AIReviewConfiguration.class,
                new DBParam("OLLAMA_URL", DEFAULT_OLLAMA_URL),
                new DBParam("OLLAMA_MODEL", DEFAULT_OLLAMA_MODEL));

        // Set remaining default values
        config.setOllamaUrl(DEFAULT_OLLAMA_URL);
        config.setOllamaModel(DEFAULT_OLLAMA_MODEL);
        config.setFallbackModel(DEFAULT_FALLBACK_MODEL);
        config.setMaxCharsPerChunk(DEFAULT_MAX_CHARS_PER_CHUNK);
        config.setMaxFilesPerChunk(DEFAULT_MAX_FILES_PER_CHUNK);
        config.setMaxChunks(DEFAULT_MAX_CHUNKS);
        config.setParallelChunkThreads(DEFAULT_PARALLEL_THREADS);
        config.setMaxConcurrentReviews(DEFAULT_MAX_CONCURRENT_REVIEWS);
        config.setMaxQueuedReviews(DEFAULT_MAX_QUEUED_REVIEWS);
        config.setRepoRateLimitPerHour(DEFAULT_REPO_RATE_LIMIT_PER_HOUR);
        config.setProjectRateLimitPerHour(DEFAULT_PROJECT_RATE_LIMIT_PER_HOUR);
        config.setPriorityProjects(DEFAULT_PRIORITY_PROJECTS);
        config.setPriorityRepositories(DEFAULT_PRIORITY_REPOSITORIES);
        config.setPrioritySnoozeMinutes(DEFAULT_PRIORITY_RATE_LIMIT_SNOOZE_MINUTES);
        config.setPriorityRepoRateLimit(DEFAULT_PRIORITY_REPO_RATE_LIMIT_PER_HOUR);
        config.setPriorityProjectRateLimit(DEFAULT_PRIORITY_PROJECT_RATE_LIMIT_PER_HOUR);
        config.setRepoAlertPercent(DEFAULT_REPO_ALERT_PERCENT);
        config.setProjectAlertPercent(DEFAULT_PROJECT_ALERT_PERCENT);
        config.setRepoAlertOverrides(DEFAULT_REPO_ALERT_OVERRIDES);
        config.setProjectAlertOverrides(DEFAULT_PROJECT_ALERT_OVERRIDES);
        config.setConnectTimeout(DEFAULT_CONNECT_TIMEOUT);
        config.setReadTimeout(DEFAULT_READ_TIMEOUT);
        config.setOllamaTimeout(DEFAULT_OLLAMA_TIMEOUT);
        config.setMaxIssuesPerFile(DEFAULT_MAX_ISSUES_PER_FILE);
        config.setMaxIssueComments(DEFAULT_MAX_ISSUE_COMMENTS);
        config.setMaxDiffSize(DEFAULT_MAX_DIFF_SIZE);
        config.setMaxRetries(DEFAULT_MAX_RETRIES);
        config.setBaseRetryDelayMs(DEFAULT_BASE_RETRY_DELAY);
        config.setApiDelayMs(DEFAULT_API_DELAY);
        config.setMinSeverity(DEFAULT_MIN_SEVERITY);
        config.setRequireApprovalFor(DEFAULT_REQUIRE_APPROVAL_FOR);
        config.setReviewExtensions(DEFAULT_REVIEW_EXTENSIONS);
        config.setIgnorePatterns(DEFAULT_IGNORE_PATTERNS);
        config.setIgnorePaths(DEFAULT_IGNORE_PATHS);
        config.setReviewProfileKey(DEFAULT_REVIEW_PROFILE_KEY);
        config.setEnabled(DEFAULT_ENABLED);
        config.setReviewDraftPRs(DEFAULT_REVIEW_DRAFT_PRS);
        config.setSkipGeneratedFiles(DEFAULT_SKIP_GENERATED);
        config.setSkipTests(DEFAULT_SKIP_TESTS);
        config.setAutoApprove(DEFAULT_AUTO_APPROVE);
        config.setWorkerDegradationEnabled(DEFAULT_WORKER_DEGRADATION_ENABLED);
        config.setReviewerUserSlug(null);
        config.setGlobalDefault(true);
        long now = System.currentTimeMillis();
        config.setCreatedDate(now);
        config.setModifiedDate(now);

        config.save();
        return config;
    }

    private void updateConfigurationFields(AIReviewConfiguration config, Map<String, Object> configMap) {
        normalizeParallelChunkKeys(configMap);
        // Update each field if present in the map
        if (configMap.containsKey("ollamaUrl")) {
            config.setOllamaUrl((String) configMap.get("ollamaUrl"));
        }
        if (configMap.containsKey("ollamaModel")) {
            config.setOllamaModel((String) configMap.get("ollamaModel"));
        }
        if (configMap.containsKey("fallbackModel")) {
            config.setFallbackModel((String) configMap.get("fallbackModel"));
        }
        if (configMap.containsKey("maxCharsPerChunk")) {
            config.setMaxCharsPerChunk(getIntValue(configMap, "maxCharsPerChunk"));
        }
        if (configMap.containsKey("maxFilesPerChunk")) {
            config.setMaxFilesPerChunk(getIntValue(configMap, "maxFilesPerChunk"));
        }
        if (configMap.containsKey("maxChunks")) {
            config.setMaxChunks(getIntValue(configMap, "maxChunks"));
        }
        if (configMap.containsKey("parallelThreads")) {
            config.setParallelChunkThreads(getIntValue(configMap, "parallelThreads"));
        }
        if (configMap.containsKey("maxConcurrentReviews")) {
            config.setMaxConcurrentReviews(getIntValue(configMap, "maxConcurrentReviews"));
        }
        if (configMap.containsKey("maxQueuedReviews")) {
            config.setMaxQueuedReviews(getIntValue(configMap, "maxQueuedReviews"));
        }
        if (configMap.containsKey("maxQueuedPerRepo")) {
            config.setMaxQueuedPerRepo(getIntValue(configMap, "maxQueuedPerRepo"));
        }
        if (configMap.containsKey("maxQueuedPerProject")) {
            config.setMaxQueuedPerProject(getIntValue(configMap, "maxQueuedPerProject"));
        }
        if (configMap.containsKey("repoRateLimitPerHour")) {
            config.setRepoRateLimitPerHour(getIntValue(configMap, "repoRateLimitPerHour"));
        }
        if (configMap.containsKey("projectRateLimitPerHour")) {
            config.setProjectRateLimitPerHour(getIntValue(configMap, "projectRateLimitPerHour"));
        }
        if (configMap.containsKey("priorityProjects")) {
            config.setPriorityProjects(normalizeScopeListValue(configMap.get("priorityProjects")));
        }
        if (configMap.containsKey("priorityRepositories")) {
            config.setPriorityRepositories(normalizeScopeListValue(configMap.get("priorityRepositories")));
        }
        if (configMap.containsKey("priorityRateLimitSnoozeMinutes")) {
            config.setPrioritySnoozeMinutes(getIntValue(configMap, "priorityRateLimitSnoozeMinutes"));
        }
        if (configMap.containsKey("priorityRepoRateLimitPerHour")) {
            config.setPriorityRepoRateLimit(getIntValue(configMap, "priorityRepoRateLimitPerHour"));
        }
        if (configMap.containsKey("priorityProjectRateLimitPerHour")) {
            config.setPriorityProjectRateLimit(getIntValue(configMap, "priorityProjectRateLimitPerHour"));
        }
        if (configMap.containsKey("repoRateLimitAlertPercent")) {
            config.setRepoAlertPercent(getIntValue(configMap, "repoRateLimitAlertPercent"));
        }
        if (configMap.containsKey("projectRateLimitAlertPercent")) {
            config.setProjectAlertPercent(getIntValue(configMap, "projectRateLimitAlertPercent"));
        }
        if (configMap.containsKey("repoRateLimitAlertOverrides")) {
            config.setRepoAlertOverrides(normalizeOverridesValue(configMap.get("repoRateLimitAlertOverrides")));
        }
        if (configMap.containsKey("projectRateLimitAlertOverrides")) {
            config.setProjectAlertOverrides(normalizeOverridesValue(configMap.get("projectRateLimitAlertOverrides")));
        }
        if (configMap.containsKey("connectTimeout")) {
            config.setConnectTimeout(getIntValue(configMap, "connectTimeout"));
        }
        if (configMap.containsKey("readTimeout")) {
            config.setReadTimeout(getIntValue(configMap, "readTimeout"));
        }
        if (configMap.containsKey("ollamaTimeout")) {
            config.setOllamaTimeout(getIntValue(configMap, "ollamaTimeout"));
        }
        if (configMap.containsKey("maxIssuesPerFile")) {
            config.setMaxIssuesPerFile(getIntValue(configMap, "maxIssuesPerFile"));
        }
        if (configMap.containsKey("maxIssueComments")) {
            config.setMaxIssueComments(getIntValue(configMap, "maxIssueComments"));
        }
        if (configMap.containsKey("maxDiffSize")) {
            config.setMaxDiffSize(getIntValue(configMap, "maxDiffSize"));
        }
        if (configMap.containsKey("maxRetries")) {
            config.setMaxRetries(getIntValue(configMap, "maxRetries"));
        }
        if (configMap.containsKey("baseRetryDelay")) {
            config.setBaseRetryDelayMs(getIntValue(configMap, "baseRetryDelay"));
        }
        if (configMap.containsKey("apiDelayMs")) {
            config.setApiDelayMs(getIntValue(configMap, "apiDelayMs"));
        }
        if (configMap.containsKey("minSeverity")) {
            config.setMinSeverity((String) configMap.get("minSeverity"));
        }
        if (configMap.containsKey("requireApprovalFor")) {
            config.setRequireApprovalFor((String) configMap.get("requireApprovalFor"));
        }
        if (configMap.containsKey("reviewExtensions")) {
            config.setReviewExtensions((String) configMap.get("reviewExtensions"));
        }
        if (configMap.containsKey("ignorePatterns")) {
            config.setIgnorePatterns((String) configMap.get("ignorePatterns"));
        }
        if (configMap.containsKey("ignorePaths")) {
            config.setIgnorePaths((String) configMap.get("ignorePaths"));
        }
        if (configMap.containsKey("reviewProfile")) {
            String key = (String) configMap.get("reviewProfile");
            config.setReviewProfileKey(defaultString(key, DEFAULT_REVIEW_PROFILE_KEY));
        }
        if (configMap.containsKey("enabled")) {
            config.setEnabled(getBooleanValue(configMap, "enabled"));
        }
        if (configMap.containsKey("reviewDraftPRs")) {
            config.setReviewDraftPRs(getBooleanValue(configMap, "reviewDraftPRs"));
        }
        if (configMap.containsKey("skipGeneratedFiles")) {
            config.setSkipGeneratedFiles(getBooleanValue(configMap, "skipGeneratedFiles"));
        }
        if (configMap.containsKey("skipTests")) {
            config.setSkipTests(getBooleanValue(configMap, "skipTests"));
        }
        if (configMap.containsKey("autoApprove")) {
            config.setAutoApprove(getBooleanValue(configMap, "autoApprove"));
        }
        if (configMap.containsKey("workerDegradationEnabled")) {
            config.setWorkerDegradationEnabled(getBooleanValue(configMap, "workerDegradationEnabled"));
        }
        if (configMap.containsKey("aiReviewerUser")) {
            config.setReviewerUserSlug(trimToNull(configMap.get("aiReviewerUser")));
        }
        if (configMap.containsKey("scopeMode")) {
            ScopeMode mode = ScopeMode.fromString(String.valueOf(configMap.get("scopeMode")));
            config.setScopeMode(mode.toConfigValue());
        }
    }

    private String defaultString(String value, String defaultValue) {
        return value != null && !value.trim().isEmpty() ? value : defaultValue;
    }

    private int defaultInt(Integer value, int defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        return value > 0 ? value : defaultValue;
    }

    private int defaultIntAllowZero(Integer value, int defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        return value >= 0 ? value : defaultValue;
    }

    private boolean defaultBoolean(Boolean value, boolean defaultValue) {
        return value != null ? value : defaultValue;
    }

    private boolean applyMissingDefaults(AIReviewConfiguration config, long timestamp) {
        boolean updated = false;

        if (isBlank(config.getOllamaUrl())) {
            config.setOllamaUrl(DEFAULT_OLLAMA_URL);
            updated = true;
        }
        if (isBlank(config.getOllamaModel())) {
            config.setOllamaModel(DEFAULT_OLLAMA_MODEL);
            updated = true;
        }
        if (isBlank(config.getFallbackModel())) {
            config.setFallbackModel(DEFAULT_FALLBACK_MODEL);
            updated = true;
        }

        if (config.getMaxCharsPerChunk() <= 0) {
            config.setMaxCharsPerChunk(DEFAULT_MAX_CHARS_PER_CHUNK);
            updated = true;
        }
        if (config.getMaxFilesPerChunk() <= 0) {
            config.setMaxFilesPerChunk(DEFAULT_MAX_FILES_PER_CHUNK);
            updated = true;
        }
        if (config.getMaxChunks() <= 0) {
            config.setMaxChunks(DEFAULT_MAX_CHUNKS);
            updated = true;
        }
        if (config.getParallelChunkThreads() <= 0) {
            config.setParallelChunkThreads(DEFAULT_PARALLEL_THREADS);
            updated = true;
        }
        if (config.getMaxConcurrentReviews() <= 0) {
            config.setMaxConcurrentReviews(DEFAULT_MAX_CONCURRENT_REVIEWS);
            updated = true;
        }
        if (config.getMaxQueuedReviews() <= 0) {
            config.setMaxQueuedReviews(DEFAULT_MAX_QUEUED_REVIEWS);
            updated = true;
        }
        if (config.getMaxQueuedPerRepo() <= 0) {
            config.setMaxQueuedPerRepo(DEFAULT_MAX_QUEUED_PER_REPO);
            updated = true;
        }
        if (config.getMaxQueuedPerProject() <= 0) {
            config.setMaxQueuedPerProject(DEFAULT_MAX_QUEUED_PER_PROJECT);
            updated = true;
        }
        if (config.getRepoRateLimitPerHour() < 0) {
            config.setRepoRateLimitPerHour(DEFAULT_REPO_RATE_LIMIT_PER_HOUR);
            updated = true;
        }
        if (config.getProjectRateLimitPerHour() < 0) {
            config.setProjectRateLimitPerHour(DEFAULT_PROJECT_RATE_LIMIT_PER_HOUR);
            updated = true;
        }
        if (isBlank(config.getPriorityProjects())) {
            config.setPriorityProjects(DEFAULT_PRIORITY_PROJECTS);
            updated = true;
        }
        if (isBlank(config.getPriorityRepositories())) {
            config.setPriorityRepositories(DEFAULT_PRIORITY_REPOSITORIES);
            updated = true;
        }
        if (config.getPrioritySnoozeMinutes() <= 0) {
            config.setPrioritySnoozeMinutes(DEFAULT_PRIORITY_RATE_LIMIT_SNOOZE_MINUTES);
            updated = true;
        }
        if (config.getPriorityRepoRateLimit() <= 0) {
            config.setPriorityRepoRateLimit(DEFAULT_PRIORITY_REPO_RATE_LIMIT_PER_HOUR);
            updated = true;
        }
        if (config.getPriorityProjectRateLimit() <= 0) {
            config.setPriorityProjectRateLimit(DEFAULT_PRIORITY_PROJECT_RATE_LIMIT_PER_HOUR);
            updated = true;
        }
        if (config.getRepoAlertPercent() <= 0) {
            config.setRepoAlertPercent(DEFAULT_REPO_ALERT_PERCENT);
            updated = true;
        }
        if (config.getProjectAlertPercent() <= 0) {
            config.setProjectAlertPercent(DEFAULT_PROJECT_ALERT_PERCENT);
            updated = true;
        }
        if (isBlank(config.getRepoAlertOverrides())) {
            config.setRepoAlertOverrides(DEFAULT_REPO_ALERT_OVERRIDES);
            updated = true;
        }
        if (isBlank(config.getProjectAlertOverrides())) {
            config.setProjectAlertOverrides(DEFAULT_PROJECT_ALERT_OVERRIDES);
            updated = true;
        }

        if (config.getConnectTimeout() <= 0) {
            config.setConnectTimeout(DEFAULT_CONNECT_TIMEOUT);
            updated = true;
        }
        if (config.getReadTimeout() <= 0) {
            config.setReadTimeout(DEFAULT_READ_TIMEOUT);
            updated = true;
        }
        if (config.getOllamaTimeout() <= 0) {
            config.setOllamaTimeout(DEFAULT_OLLAMA_TIMEOUT);
            updated = true;
        }

        if (config.getMaxIssuesPerFile() <= 0) {
            config.setMaxIssuesPerFile(DEFAULT_MAX_ISSUES_PER_FILE);
            updated = true;
        }
        if (config.getMaxIssueComments() <= 0) {
            config.setMaxIssueComments(DEFAULT_MAX_ISSUE_COMMENTS);
            updated = true;
        }
        if (config.getMaxDiffSize() <= 0) {
            config.setMaxDiffSize(DEFAULT_MAX_DIFF_SIZE);
            updated = true;
        }
        if (config.getMaxRetries() < 0) {
            config.setMaxRetries(DEFAULT_MAX_RETRIES);
            updated = true;
        }
        if (config.getBaseRetryDelayMs() <= 0) {
            config.setBaseRetryDelayMs(DEFAULT_BASE_RETRY_DELAY);
            updated = true;
        }
        if (config.getApiDelayMs() <= 0) {
            config.setApiDelayMs(DEFAULT_API_DELAY);
            updated = true;
        }

        if (isBlank(config.getReviewExtensions())) {
            config.setReviewExtensions(DEFAULT_REVIEW_EXTENSIONS);
            updated = true;
        }
        if (isBlank(config.getIgnorePatterns())) {
            config.setIgnorePatterns(DEFAULT_IGNORE_PATTERNS);
            updated = true;
        }
        if (isBlank(config.getIgnorePaths())) {
            config.setIgnorePaths(DEFAULT_IGNORE_PATHS);
            updated = true;
        }

        if (isBlank(config.getScopeMode())) {
            config.setScopeMode(DEFAULT_SCOPE_MODE);
            updated = true;
        }

        if (config.getMinSeverity() == null || config.getMinSeverity().trim().isEmpty()) {
            config.setMinSeverity(DEFAULT_MIN_SEVERITY);
            updated = true;
        }
        if (config.getRequireApprovalFor() == null) {
            config.setRequireApprovalFor(DEFAULT_REQUIRE_APPROVAL_FOR);
            updated = true;
        }
        if (isBlank(config.getReviewProfileKey())) {
            config.setReviewProfileKey(DEFAULT_REVIEW_PROFILE_KEY);
            updated = true;
        }

        if (config.getCreatedDate() == 0L) {
            config.setCreatedDate(timestamp);
            updated = true;
        }

        return updated;
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private List<String> splitList(@Nullable String raw) {
        if (raw == null) {
            return Collections.emptyList();
        }
        String[] tokens = raw.split("[,\\n]");
        List<String> values = new ArrayList<>(tokens.length);
        for (String token : tokens) {
            if (token == null) {
                continue;
            }
            String trimmed = token.trim();
            if (!trimmed.isEmpty()) {
                values.add(trimmed);
            }
        }
        return values;
    }

    private String normalizeScopeListValue(@Nullable Object raw) {
        if (raw == null) {
            return "";
        }
        if (raw instanceof Collection) {
            Collection<?> collection = (Collection<?>) raw;
            return collection.stream()
                    .filter(Objects::nonNull)
                    .map(Object::toString)
                    .map(String::trim)
                    .filter(value -> !value.isEmpty())
                    .collect(Collectors.joining(","));
        }
        return raw.toString();
    }

    private String normalizeOverridesValue(@Nullable Object raw) {
        if (raw == null) {
            return "";
        }
        if (raw instanceof Map) {
            Map<?, ?> map = (Map<?, ?>) raw;
            return map.entrySet().stream()
                    .filter(entry -> entry.getKey() != null && entry.getValue() != null)
                    .map(entry -> entry.getKey().toString().trim() + "=" + entry.getValue().toString().trim())
                    .collect(Collectors.joining(","));
        }
        if (raw instanceof Collection) {
            Collection<?> collection = (Collection<?>) raw;
            return collection.stream()
                    .filter(Objects::nonNull)
                    .map(Object::toString)
                    .map(String::trim)
                    .filter(value -> !value.isEmpty())
                    .collect(Collectors.joining(","));
        }
        return raw.toString();
    }

    private Set<String> parsePriorityProjects(@Nullable String raw) {
        if (isBlank(raw)) {
            return Collections.emptySet();
        }
        Set<String> projects = new LinkedHashSet<>();
        for (String entry : splitList(raw)) {
            String normalized = canonicalProjectKey(entry);
            if (normalized != null) {
                projects.add(normalized);
            }
        }
        return projects;
    }

    private Set<String> parsePriorityRepositories(@Nullable String raw) {
        if (isBlank(raw)) {
            return Collections.emptySet();
        }
        Set<String> repos = new LinkedHashSet<>();
        for (String entry : splitList(raw)) {
            int slash = entry.indexOf('/');
            if (slash <= 0 || slash >= entry.length() - 1) {
                continue;
            }
            String project = entry.substring(0, slash);
            String repo = entry.substring(slash + 1);
            String normalized = canonicalRepoKey(project, repo);
            if (normalized != null) {
                repos.add(normalized);
            }
        }
        return repos;
    }

    private String canonicalProjectKey(@Nullable String projectKey) {
        if (projectKey == null) {
            return null;
        }
        String trimmed = projectKey.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        return trimmed.toUpperCase(Locale.ROOT);
    }

    private String canonicalRepoSlug(@Nullable String repositorySlug) {
        if (repositorySlug == null) {
            return null;
        }
        String trimmed = repositorySlug.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        int slash = trimmed.indexOf('/');
        if (slash >= 0 && slash < trimmed.length() - 1) {
            trimmed = trimmed.substring(slash + 1);
        }
        if (trimmed.isEmpty()) {
            return null;
        }
        return trimmed.toLowerCase(Locale.ROOT);
    }

    private String canonicalRepoKey(@Nullable String projectKey, @Nullable String repositorySlug) {
        String project = canonicalProjectKey(projectKey);
        if (project == null || repositorySlug == null) {
            return null;
        }
        String slug = repositorySlug.trim().toLowerCase(Locale.ROOT);
        if (slug.isEmpty()) {
            return null;
        }
        return project + "/" + slug;
    }

    private Map<String, Object> convertToMap(AIReviewConfiguration config) {
        Map<String, Object> map = new HashMap<>();
        map.put("ollamaUrl", defaultString(config.getOllamaUrl(), DEFAULT_OLLAMA_URL));
        map.put("ollamaModel", defaultString(config.getOllamaModel(), DEFAULT_OLLAMA_MODEL));
        map.put("fallbackModel", defaultString(config.getFallbackModel(), DEFAULT_FALLBACK_MODEL));
        map.put("maxCharsPerChunk", defaultInt(config.getMaxCharsPerChunk(), DEFAULT_MAX_CHARS_PER_CHUNK));
        map.put("maxFilesPerChunk", defaultInt(config.getMaxFilesPerChunk(), DEFAULT_MAX_FILES_PER_CHUNK));
        map.put("maxChunks", defaultInt(config.getMaxChunks(), DEFAULT_MAX_CHUNKS));
        int parallel = defaultInt(config.getParallelChunkThreads(), DEFAULT_PARALLEL_THREADS);
        map.put(KEY_PARALLEL_THREADS, parallel);
        map.put(KEY_MAX_PARALLEL_CHUNKS, parallel);
        map.put("maxConcurrentReviews", defaultInt(config.getMaxConcurrentReviews(), DEFAULT_MAX_CONCURRENT_REVIEWS));
        map.put("maxQueuedReviews", defaultInt(config.getMaxQueuedReviews(), DEFAULT_MAX_QUEUED_REVIEWS));
        map.put("maxQueuedPerRepo", defaultInt(config.getMaxQueuedPerRepo(), DEFAULT_MAX_QUEUED_PER_REPO));
        map.put("maxQueuedPerProject", defaultInt(config.getMaxQueuedPerProject(), DEFAULT_MAX_QUEUED_PER_PROJECT));
        map.put("repoRateLimitPerHour", defaultIntAllowZero(config.getRepoRateLimitPerHour(), DEFAULT_REPO_RATE_LIMIT_PER_HOUR));
        map.put("projectRateLimitPerHour", defaultIntAllowZero(config.getProjectRateLimitPerHour(), DEFAULT_PROJECT_RATE_LIMIT_PER_HOUR));
        map.put("priorityProjects", defaultString(config.getPriorityProjects(), DEFAULT_PRIORITY_PROJECTS));
        map.put("priorityRepositories", defaultString(config.getPriorityRepositories(), DEFAULT_PRIORITY_REPOSITORIES));
        map.put("priorityRateLimitSnoozeMinutes",
                defaultInt(config.getPrioritySnoozeMinutes(), DEFAULT_PRIORITY_RATE_LIMIT_SNOOZE_MINUTES));
        map.put("priorityRepoRateLimitPerHour",
                defaultIntAllowZero(config.getPriorityRepoRateLimit(), DEFAULT_PRIORITY_REPO_RATE_LIMIT_PER_HOUR));
        map.put("priorityProjectRateLimitPerHour",
                defaultIntAllowZero(config.getPriorityProjectRateLimit(), DEFAULT_PRIORITY_PROJECT_RATE_LIMIT_PER_HOUR));
        map.put("repoRateLimitAlertPercent",
                defaultInt(config.getRepoAlertPercent(), DEFAULT_REPO_ALERT_PERCENT));
        map.put("projectRateLimitAlertPercent",
                defaultInt(config.getProjectAlertPercent(), DEFAULT_PROJECT_ALERT_PERCENT));
        map.put("repoRateLimitAlertOverrides",
                defaultString(config.getRepoAlertOverrides(), DEFAULT_REPO_ALERT_OVERRIDES));
        map.put("projectRateLimitAlertOverrides",
                defaultString(config.getProjectAlertOverrides(), DEFAULT_PROJECT_ALERT_OVERRIDES));
        map.put("connectTimeout", defaultInt(config.getConnectTimeout(), DEFAULT_CONNECT_TIMEOUT));
        map.put("readTimeout", defaultInt(config.getReadTimeout(), DEFAULT_READ_TIMEOUT));
        map.put("ollamaTimeout", defaultInt(config.getOllamaTimeout(), DEFAULT_OLLAMA_TIMEOUT));
        map.put("maxIssuesPerFile", defaultInt(config.getMaxIssuesPerFile(), DEFAULT_MAX_ISSUES_PER_FILE));
        map.put("maxIssueComments", defaultInt(config.getMaxIssueComments(), DEFAULT_MAX_ISSUE_COMMENTS));
        map.put("maxDiffSize", defaultInt(config.getMaxDiffSize(), DEFAULT_MAX_DIFF_SIZE));
        map.put("maxRetries", defaultInt(config.getMaxRetries(), DEFAULT_MAX_RETRIES));
        map.put("baseRetryDelay", defaultInt(config.getBaseRetryDelayMs(), DEFAULT_BASE_RETRY_DELAY));
        map.put("apiDelayMs", defaultInt(config.getApiDelayMs(), DEFAULT_API_DELAY));
        map.put("minSeverity", defaultString(config.getMinSeverity(), DEFAULT_MIN_SEVERITY));
        map.put("requireApprovalFor", defaultString(config.getRequireApprovalFor(), DEFAULT_REQUIRE_APPROVAL_FOR));
        map.put("reviewExtensions", defaultString(config.getReviewExtensions(), DEFAULT_REVIEW_EXTENSIONS));
        map.put("ignorePatterns", defaultString(config.getIgnorePatterns(), DEFAULT_IGNORE_PATTERNS));
        map.put("ignorePaths", defaultString(config.getIgnorePaths(), DEFAULT_IGNORE_PATHS));
        map.put("reviewProfile", defaultString(config.getReviewProfileKey(), DEFAULT_REVIEW_PROFILE_KEY));
        map.put("enabled", defaultBoolean(config.isEnabled(), DEFAULT_ENABLED));
        map.put("reviewDraftPRs", defaultBoolean(config.isReviewDraftPRs(), DEFAULT_REVIEW_DRAFT_PRS));
        map.put("skipGeneratedFiles", defaultBoolean(config.isSkipGeneratedFiles(), DEFAULT_SKIP_GENERATED));
        map.put("skipTests", defaultBoolean(config.isSkipTests(), DEFAULT_SKIP_TESTS));
        map.put("autoApprove", defaultBoolean(config.isAutoApprove(), DEFAULT_AUTO_APPROVE));
        map.put("workerDegradationEnabled", defaultBoolean(config.isWorkerDegradationEnabled(), DEFAULT_WORKER_DEGRADATION_ENABLED));
        map.put("aiReviewerUser", trimToNull(config.getReviewerUserSlug()));
        map.put("aiReviewerUserDisplayName", resolveUserDisplayName(config.getReviewerUserSlug()));
        map.put("scopeMode", defaultString(config.getScopeMode(), DEFAULT_SCOPE_MODE));
        return map;
    }

    private Map<String, Integer> parseRepoAlertOverrides(@Nullable String raw) {
        return parseAlertOverrides(raw, false);
    }

    private Map<String, Integer> parseProjectAlertOverrides(@Nullable String raw) {
        return parseAlertOverrides(raw, true);
    }

    private Map<String, Integer> parseAlertOverrides(@Nullable String raw, boolean project) {
        if (isBlank(raw)) {
            return Collections.emptyMap();
        }
        Map<String, Integer> overrides = new LinkedHashMap<>();
        for (String entry : splitList(raw)) {
            int idx = entry.indexOf('=');
            if (idx <= 0 || idx >= entry.length() - 1) {
                continue;
            }
            String scope = entry.substring(0, idx).trim();
            String percentRaw = entry.substring(idx + 1).trim();
            Integer percent = parseInteger(percentRaw);
            if (percent == null) {
                continue;
            }
            int clamped = clampPercent(percent);
            if (project) {
                String key = canonicalProjectKey(scope);
                if (key != null) {
                    overrides.put(key, clamped);
                }
            } else {
                String key = canonicalRepoSlug(scope);
                if (key != null) {
                    overrides.put(key, clamped);
                }
            }
        }
        return overrides;
    }

    private int clampPercent(int percent) {
        return Math.max(1, Math.min(100, percent));
    }

    private void normalizeParallelChunkKeys(Map<String, Object> configMap) {
        if (configMap == null) {
            return;
        }
        Object maxParallel = configMap.get(KEY_MAX_PARALLEL_CHUNKS);
        Object threads = configMap.get(KEY_PARALLEL_THREADS);
        if (maxParallel != null && threads == null) {
            configMap.put(KEY_PARALLEL_THREADS, maxParallel);
        } else if (threads != null && maxParallel == null) {
            configMap.put(KEY_MAX_PARALLEL_CHUNKS, threads);
        }
    }

    private void validateUrl(String url) {
        try {
            URI uri = URI.create(url);
            String scheme = uri.getScheme();
            if (scheme == null || (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme))) {
                throw new IllegalArgumentException("URL must use http or https: " + url);
            }
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid URL format: " + url, e);
        }
    }

    private void validateIntegerRange(Map<String, Object> configMap,
                                      String key,
                                      int min,
                                      int max,
                                      Map<String, String> errors) {
        if (!configMap.containsKey(key)) {
            return;
        }
        Integer value = parseInteger(configMap.get(key));
        if (value == null) {
            errors.put(key, "Expected whole number value");
            return;
        }
        if (value < min || value > max) {
            errors.put(key, String.format("Value must be between %d and %d", min, max));
        }
    }

    private Integer parseInteger(Object raw) {
        if (raw instanceof Integer) {
            return (Integer) raw;
        }
        if (raw instanceof Number) {
            return ((Number) raw).intValue();
        }
        if (raw instanceof String) {
            try {
                String trimmed = ((String) raw).trim();
                if (trimmed.isEmpty()) {
                    return null;
                }
                return Integer.parseInt(trimmed);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private void validateAlertOverrides(Map<String, Object> configMap,
                                        String key,
                                        boolean project,
                                        Map<String, String> errors) {
        if (!configMap.containsKey(key)) {
            return;
        }
        String normalized = normalizeOverridesValue(configMap.get(key));
        if (normalized.isEmpty()) {
            configMap.put(key, "");
            return;
        }
        for (String entry : splitList(normalized)) {
            int idx = entry.indexOf('=');
            if (idx <= 0 || idx >= entry.length() - 1) {
                errors.put(key, "Invalid entry '" + entry + "'. Expected scope=percent.");
                return;
            }
            String scope = entry.substring(0, idx).trim();
            String percentRaw = entry.substring(idx + 1).trim();
            Integer percent = parseInteger(percentRaw);
            if (percent == null || percent < 1 || percent > 100) {
                errors.put(key, "Percent out of range for '" + scope + "'");
                return;
            }
            if (project) {
                if (canonicalProjectKey(scope) == null) {
                    errors.put(key, "Invalid project key '" + scope + "'");
                    return;
                }
            } else {
                String slug = canonicalRepoSlug(scope);
                if (slug == null || !REPO_SLUG_PATTERN.matcher(slug).matches()) {
                    errors.put(key, "Invalid repository slug '" + scope + "'");
                    return;
                }
            }
        }
        configMap.put(key, normalized);
    }

    private void validatePriorityScopeList(String key,
                                           @Nullable String value,
                                           boolean projects,
                                           Map<String, String> errors) {
        if (value == null || value.trim().isEmpty()) {
            return;
        }
        for (String entry : splitList(value)) {
            if (projects) {
                if (!PRIORITY_PROJECT_PATTERN.matcher(entry).matches()) {
                    errors.put(key, "Invalid project key '" + entry + "'");
                    return;
                }
            } else if (!PRIORITY_REPO_PATTERN.matcher(entry).matches()) {
                errors.put(key, "Invalid repository coordinate '" + entry + "' (expected PROJECT/slug)");
                return;
            }
        }
    }

    private boolean isValidSeverity(String severity) {
        return "low".equals(severity) || "medium".equals(severity) ||
               "high".equals(severity) || "critical".equals(severity);
    }

    private int getIntValue(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value instanceof Integer) {
            return (Integer) value;
        } else if (value instanceof Number) {
            return ((Number) value).intValue();
        } else if (value instanceof String) {
            return Integer.parseInt((String) value);
        }
        throw new IllegalArgumentException("Cannot convert " + key + " to integer: " + value);
    }

    private boolean getBooleanValue(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value instanceof Boolean) {
            return (Boolean) value;
        } else if (value instanceof String) {
            return Boolean.parseBoolean((String) value);
        }
        throw new IllegalArgumentException("Cannot convert " + key + " to boolean: " + value);
    }

    private String resolveUserDisplayName(String userSlug) {
        if (userSlug == null || userSlug.trim().isEmpty() || userService == null) {
            return null;
        }
        try {
            ApplicationUser user = userService.getUserBySlug(userSlug);
            if (user == null) {
                return null;
            }
            return user.getDisplayName();
        } catch (Exception e) {
            log.debug("Failed to resolve display name for user slug {}", userSlug, e);
            return null;
        }
    }

    private String trimToNull(Object value) {
        if (!(value instanceof String)) {
            return null;
        }
        String trimmed = ((String) value).trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
