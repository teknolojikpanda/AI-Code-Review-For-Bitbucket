package com.example.bitbucket.aireviewer.service;

import com.atlassian.activeobjects.external.ActiveObjects;
import com.atlassian.plugin.spring.scanner.annotation.export.ExportAsService;
import com.atlassian.plugin.spring.scanner.annotation.imports.ComponentImport;
import com.example.bitbucket.aireviewer.ao.AIReviewConfiguration;
import com.example.bitbucket.aireviewer.util.HttpClientUtil;
import net.java.ao.Query;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import javax.inject.Inject;
import javax.inject.Named;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Implementation of AIReviewerConfigService using Active Objects for persistence.
 */
@Named
@ExportAsService(AIReviewerConfigService.class)
public class AIReviewerConfigServiceImpl implements AIReviewerConfigService {

    private static final Logger log = LoggerFactory.getLogger(AIReviewerConfigServiceImpl.class);

    private final ActiveObjects ao;
    private final HttpClientUtil httpClientUtil;

    // Default configuration values
    private static final String DEFAULT_OLLAMA_URL = "http://10.152.98.37:11434";
    private static final String DEFAULT_OLLAMA_MODEL = "qwen3-coder:30b";
    private static final String DEFAULT_FALLBACK_MODEL = "qwen3-coder:7b";
    private static final int DEFAULT_MAX_CHARS_PER_CHUNK = 60000;
    private static final int DEFAULT_MAX_FILES_PER_CHUNK = 3;
    private static final int DEFAULT_MAX_CHUNKS = 20;
    private static final int DEFAULT_PARALLEL_THREADS = 4;
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
    private static final boolean DEFAULT_ENABLED = true;
    private static final boolean DEFAULT_REVIEW_DRAFT_PRS = false;
    private static final boolean DEFAULT_SKIP_GENERATED = true;
    private static final boolean DEFAULT_SKIP_TESTS = false;
    private static final boolean DEFAULT_AUTO_APPROVE = false;

    @Inject
    public AIReviewerConfigServiceImpl(
            @ComponentImport ActiveObjects ao) {
        this.ao = Objects.requireNonNull(ao, "activeObjects cannot be null");
        this.httpClientUtil = new HttpClientUtil(); // Create with default settings
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

            // Return the first (and should be only) configuration
            return configs[0];
        });
    }

    @Nonnull
    @Override
    public AIReviewConfiguration updateConfiguration(@Nonnull Map<String, Object> configMap) {
        Objects.requireNonNull(configMap, "configMap cannot be null");
        validateConfiguration(configMap);

        return ao.executeInTransaction(() -> {
            AIReviewConfiguration config = getOrCreateConfiguration();

            // Update all fields from the map
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

        // Validate Ollama URL
        String ollamaUrl = (String) configMap.get("ollamaUrl");
        if (ollamaUrl != null && !ollamaUrl.isEmpty()) {
            validateUrl(ollamaUrl);
        }

        // Validate numeric ranges
        validateIntegerRange(configMap, "maxCharsPerChunk", 10000, 100000);
        validateIntegerRange(configMap, "maxFilesPerChunk", 1, 10);
        validateIntegerRange(configMap, "maxChunks", 1, 50);
        validateIntegerRange(configMap, "parallelThreads", 1, 16);
        validateIntegerRange(configMap, "maxIssuesPerFile", 1, 100);
        validateIntegerRange(configMap, "maxIssueComments", 1, 100);
        validateIntegerRange(configMap, "maxRetries", 0, 10);

        // Validate severity
        String minSeverity = (String) configMap.get("minSeverity");
        if (minSeverity != null && !isValidSeverity(minSeverity)) {
            throw new IllegalArgumentException("Invalid severity: " + minSeverity + ". Must be one of: low, medium, high, critical");
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
        defaults.put("parallelThreads", DEFAULT_PARALLEL_THREADS);
        defaults.put("connectTimeout", DEFAULT_CONNECT_TIMEOUT);
        defaults.put("readTimeout", DEFAULT_READ_TIMEOUT);
        defaults.put("ollamaTimeout", DEFAULT_OLLAMA_TIMEOUT);
        defaults.put("maxIssuesPerFile", DEFAULT_MAX_ISSUES_PER_FILE);
        defaults.put("maxIssueComments", DEFAULT_MAX_ISSUE_COMMENTS);
        defaults.put("maxDiffSize", DEFAULT_MAX_DIFF_SIZE);
        defaults.put("maxRetries", DEFAULT_MAX_RETRIES);
        defaults.put("baseRetryDelay", DEFAULT_BASE_RETRY_DELAY);
        defaults.put("apiDelay", DEFAULT_API_DELAY);
        defaults.put("minSeverity", DEFAULT_MIN_SEVERITY);
        defaults.put("requireApprovalFor", DEFAULT_REQUIRE_APPROVAL_FOR);
        defaults.put("reviewExtensions", DEFAULT_REVIEW_EXTENSIONS);
        defaults.put("ignorePatterns", DEFAULT_IGNORE_PATTERNS);
        defaults.put("ignorePaths", DEFAULT_IGNORE_PATHS);
        defaults.put("enabled", DEFAULT_ENABLED);
        defaults.put("reviewDraftPRs", DEFAULT_REVIEW_DRAFT_PRS);
        defaults.put("skipGeneratedFiles", DEFAULT_SKIP_GENERATED);
        defaults.put("skipTests", DEFAULT_SKIP_TESTS);
        defaults.put("autoApprove", DEFAULT_AUTO_APPROVE);
        return Collections.unmodifiableMap(defaults);
    }

    // Private helper methods

    private AIReviewConfiguration getOrCreateConfiguration() {
        AIReviewConfiguration[] configs = ao.find(AIReviewConfiguration.class);

        if (configs.length == 0) {
            return createDefaultConfiguration();
        }

        return configs[0];
    }

    private AIReviewConfiguration createDefaultConfiguration() {
        AIReviewConfiguration config = ao.create(AIReviewConfiguration.class);

        // Set all default values
        config.setOllamaUrl(DEFAULT_OLLAMA_URL);
        config.setOllamaModel(DEFAULT_OLLAMA_MODEL);
        config.setFallbackModel(DEFAULT_FALLBACK_MODEL);
        config.setMaxCharsPerChunk(DEFAULT_MAX_CHARS_PER_CHUNK);
        config.setMaxFilesPerChunk(DEFAULT_MAX_FILES_PER_CHUNK);
        config.setMaxChunks(DEFAULT_MAX_CHUNKS);
        config.setParallelChunkThreads(DEFAULT_PARALLEL_THREADS);
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
        config.setEnabled(DEFAULT_ENABLED);
        config.setReviewDraftPRs(DEFAULT_REVIEW_DRAFT_PRS);
        config.setSkipGeneratedFiles(DEFAULT_SKIP_GENERATED);
        config.setSkipTests(DEFAULT_SKIP_TESTS);

        config.save();
        return config;
    }

    private void updateConfigurationFields(AIReviewConfiguration config, Map<String, Object> configMap) {
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
        if (configMap.containsKey("apiDelay")) {
            config.setApiDelayMs(getIntValue(configMap, "apiDelay"));
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
    }

    private Map<String, Object> convertToMap(AIReviewConfiguration config) {
        Map<String, Object> map = new HashMap<>();
        map.put("ollamaUrl", config.getOllamaUrl());
        map.put("ollamaModel", config.getOllamaModel());
        map.put("fallbackModel", config.getFallbackModel());
        map.put("maxCharsPerChunk", config.getMaxCharsPerChunk());
        map.put("maxFilesPerChunk", config.getMaxFilesPerChunk());
        map.put("maxChunks", config.getMaxChunks());
        map.put("parallelThreads", config.getParallelChunkThreads());
        map.put("connectTimeout", config.getConnectTimeout());
        map.put("readTimeout", config.getReadTimeout());
        map.put("ollamaTimeout", config.getOllamaTimeout());
        map.put("maxIssuesPerFile", config.getMaxIssuesPerFile());
        map.put("maxIssueComments", config.getMaxIssueComments());
        map.put("maxDiffSize", config.getMaxDiffSize());
        map.put("maxRetries", config.getMaxRetries());
        map.put("baseRetryDelay", config.getBaseRetryDelayMs());
        map.put("apiDelay", config.getApiDelayMs());
        map.put("minSeverity", config.getMinSeverity());
        map.put("requireApprovalFor", config.getRequireApprovalFor());
        map.put("reviewExtensions", config.getReviewExtensions());
        map.put("ignorePatterns", config.getIgnorePatterns());
        map.put("ignorePaths", config.getIgnorePaths());
        map.put("enabled", config.isEnabled());
        map.put("reviewDraftPRs", config.isReviewDraftPRs());
        map.put("skipGeneratedFiles", config.isSkipGeneratedFiles());
        map.put("skipTests", config.isSkipTests());
        map.put("autoApprove", config.isAutoApprove());
        return map;
    }

    private void validateUrl(String url) {
        try {
            new URL(url);
        } catch (MalformedURLException e) {
            throw new IllegalArgumentException("Invalid URL format: " + url, e);
        }
    }

    private void validateIntegerRange(Map<String, Object> configMap, String key, int min, int max) {
        if (!configMap.containsKey(key)) {
            return;
        }

        int value = getIntValue(configMap, key);
        if (value < min || value > max) {
            throw new IllegalArgumentException(
                    String.format("%s must be between %d and %d, got %d", key, min, max, value));
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

    private long getLongValue(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value instanceof Long) {
            return (Long) value;
        } else if (value instanceof Number) {
            return ((Number) value).longValue();
        } else if (value instanceof String) {
            return Long.parseLong((String) value);
        }
        throw new IllegalArgumentException("Cannot convert " + key + " to long: " + value);
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
}
