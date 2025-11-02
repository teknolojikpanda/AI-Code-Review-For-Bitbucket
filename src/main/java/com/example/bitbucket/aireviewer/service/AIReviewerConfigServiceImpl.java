package com.example.bitbucket.aireviewer.service;

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
import com.atlassian.plugin.spring.scanner.annotation.export.ExportAsService;
import com.atlassian.plugin.spring.scanner.annotation.imports.ComponentImport;
import com.example.bitbucket.aicode.model.ReviewProfilePreset;
import com.example.bitbucket.aireviewer.ao.AIReviewConfiguration;
import com.example.bitbucket.aireviewer.ao.AIReviewRepoConfiguration;
import com.example.bitbucket.aireviewer.util.HttpClientUtil;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.java.ao.DBParam;
import net.java.ao.Query;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import javax.inject.Inject;
import javax.inject.Named;
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
    private final HttpClientUtil httpClientUtil;

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<Map<String, Object>>() {};

    private static final Set<String> INTEGER_KEYS = Collections.unmodifiableSet(new LinkedHashSet<>(Arrays.asList(
            "maxCharsPerChunk",
            "maxFilesPerChunk",
            "maxChunks",
            "parallelThreads",
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
            "autoApprove"
    )));

    private static final Set<String> SUPPORTED_KEYS;

    // Default configuration values
    private static final String DEFAULT_OLLAMA_URL = "http://0.0.0.0:11434";
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
    private static final String DEFAULT_REVIEW_PROFILE_KEY = ReviewProfilePreset.BALANCED.getKey();
    private static final boolean DEFAULT_ENABLED = true;
    private static final boolean DEFAULT_REVIEW_DRAFT_PRS = false;
    private static final boolean DEFAULT_SKIP_GENERATED = true;
    private static final boolean DEFAULT_SKIP_TESTS = false;
    private static final boolean DEFAULT_AUTO_APPROVE = false;

    static {
        LinkedHashSet<String> keys = new LinkedHashSet<>(Arrays.asList(
                "ollamaUrl",
                "ollamaModel",
                "fallbackModel",
                "maxCharsPerChunk",
                "maxFilesPerChunk",
                "maxChunks",
                "parallelThreads",
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
                "autoApprove"
        ));
        SUPPORTED_KEYS = Collections.unmodifiableSet(keys);
    }

    @Inject
    public AIReviewerConfigServiceImpl(
            @ComponentImport ActiveObjects ao,
            @ComponentImport ProjectService projectService,
            @ComponentImport RepositoryService repositoryService) {
        this(ao, projectService, repositoryService, new HttpClientUtil());
    }

    AIReviewerConfigServiceImpl(ActiveObjects ao) {
        this(ao, null, null, new HttpClientUtil());
    }

    AIReviewerConfigServiceImpl(ActiveObjects ao,
                                ProjectService projectService,
                                RepositoryService repositoryService,
                                HttpClientUtil httpClientUtil) {
        this.ao = Objects.requireNonNull(ao, "activeObjects cannot be null");
        this.projectService = projectService;
        this.repositoryService = repositoryService;
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

        Map<String, String> errors = new LinkedHashMap<>();

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
        validateIntegerRange(configMap, "maxIssuesPerFile", 1, 100, errors);
        validateIntegerRange(configMap, "maxIssueComments", 1, 100, errors);
        validateIntegerRange(configMap, "maxRetries", 0, 10, errors);
        validateIntegerRange(configMap, "baseRetryDelay", 100, 60_000, errors);
        validateIntegerRange(configMap, "ollamaTimeout", 5_000, 600_000, errors);
        validateIntegerRange(configMap, "connectTimeout", 1_000, 120_000, errors);

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

        if (!errors.isEmpty()) {
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
        defaults.put("parallelThreads", DEFAULT_PARALLEL_THREADS);
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
        Map<String, Object> effective = new LinkedHashMap<>(getConfigurationAsMap());
        sanitized.forEach(effective::put);
        validateConfiguration(effective);

        ao.executeInTransaction(() -> {
            AIReviewRepoConfiguration existing = findRepoConfiguration(projectKey, repositorySlug);
            if (sanitized.isEmpty()) {
                if (existing != null) {
                    ao.delete(existing);
                }
                return null;
            }

            long now = System.currentTimeMillis();
            if (existing == null) {
                existing = ao.create(AIReviewRepoConfiguration.class,
                        new DBParam("PROJECT_KEY", projectKey),
                        new DBParam("REPOSITORY_SLUG", repositorySlug));
                existing.setCreatedDate(now);
            }
            existing.setConfigurationJson(writeConfigurationJson(sanitized));
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
        if (projectService == null || repositoryService == null) {
            log.debug("Project catalogue unavailable (projectService={}, repositoryService={})",
                    projectService != null, repositoryService != null);
            return Collections.emptyList();
        }

        List<Map<String, Object>> projects = new ArrayList<>();
        PageRequest projectRequest = new PageRequestImpl(0, 100);
        Page<Project> projectPage;

        do {
            projectPage = projectService.findAll(projectRequest);
            for (Project project : projectPage.getValues()) {
                projects.add(buildProjectCatalogueEntry(project));
            }
            projectRequest = projectPage.getNextPageRequest();
        } while (projectRequest != null && !projectPage.getIsLastPage());

        projects.sort(this::compareProjectsForCatalogue);
        return projects;
    }

    @Override
    public void synchronizeRepositoryOverrides(@Nonnull Collection<RepositoryScope> desiredRepositories,
                                               String updatedBy) {
        Objects.requireNonNull(desiredRepositories, "desiredRepositories");

        LinkedHashSet<RepositoryScope> desired = sanitizeDesiredRepositories(desiredRepositories);
        Set<String> desiredKeys = desired.stream()
                .map(this::repositoryKey)
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        Map<String, Object> baseConfig = getConfigurationAsMap();
        Map<String, Object> normalizedOverrides = normalizeOverrides(baseConfig);
        String overridesJson = writeConfigurationJson(normalizedOverrides);
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
                if (entity == null) {
                    entity = ao.create(AIReviewRepoConfiguration.class,
                            new DBParam("PROJECT_KEY", scope.getProjectKey()),
                            new DBParam("REPOSITORY_SLUG", scope.getRepositorySlug()));
                    entity.setCreatedDate(now);
                }
                entity.setConfigurationJson(overridesJson);
                entity.setModifiedDate(now);
                if (updatedBy != null) {
                    entity.setModifiedBy(updatedBy);
                }
                entity.save();
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
        config.setGlobalDefault(true);
        long now = System.currentTimeMillis();
        config.setCreatedDate(now);
        config.setModifiedDate(now);

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

    private Map<String, Object> convertToMap(AIReviewConfiguration config) {
        Map<String, Object> map = new HashMap<>();
        map.put("ollamaUrl", defaultString(config.getOllamaUrl(), DEFAULT_OLLAMA_URL));
        map.put("ollamaModel", defaultString(config.getOllamaModel(), DEFAULT_OLLAMA_MODEL));
        map.put("fallbackModel", defaultString(config.getFallbackModel(), DEFAULT_FALLBACK_MODEL));
        map.put("maxCharsPerChunk", defaultInt(config.getMaxCharsPerChunk(), DEFAULT_MAX_CHARS_PER_CHUNK));
        map.put("maxFilesPerChunk", defaultInt(config.getMaxFilesPerChunk(), DEFAULT_MAX_FILES_PER_CHUNK));
        map.put("maxChunks", defaultInt(config.getMaxChunks(), DEFAULT_MAX_CHUNKS));
        map.put("parallelThreads", defaultInt(config.getParallelChunkThreads(), DEFAULT_PARALLEL_THREADS));
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
        return map;
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

    private String trimToNull(Object value) {
        if (!(value instanceof String)) {
            return null;
        }
        String trimmed = ((String) value).trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
