package com.example.bitbucket.aireviewer.service;

import com.example.bitbucket.aireviewer.ao.AIReviewConfiguration;

import javax.annotation.Nonnull;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Service for managing AI Code Reviewer plugin configuration.
 * Handles configuration persistence using Active Objects.
 */
public interface AIReviewerConfigService {

    /**
     * Gets the global configuration for the plugin.
     * If no configuration exists, creates and returns a default configuration.
     *
     * @return the current configuration, never null
     */
    @Nonnull
    AIReviewConfiguration getGlobalConfiguration();

    /**
     * Updates the global configuration with the provided values.
     *
     * @param configMap map of configuration key-value pairs
     * @return the updated configuration
     * @throws IllegalArgumentException if validation fails
     */
    @Nonnull
    AIReviewConfiguration updateConfiguration(@Nonnull Map<String, Object> configMap);

    /**
     * Gets the configuration as a Map for REST API responses.
     *
     * @return map of configuration key-value pairs
     */
    @Nonnull
    Map<String, Object> getConfigurationAsMap();

    /**
     * Validates the provided configuration without saving it.
     *
     * @param configMap map of configuration key-value pairs to validate
     * @throws IllegalArgumentException if validation fails with details
     */
    void validateConfiguration(@Nonnull Map<String, Object> configMap);

    /**
     * Resets the configuration to default values.
     *
     * @return the reset configuration with default values
     */
    @Nonnull
    AIReviewConfiguration resetToDefaults();

    /**
     * Tests connectivity to the Ollama service.
     *
     * @param ollamaUrl the URL to test
     * @return true if connection successful, false otherwise
     */
    boolean testOllamaConnection(@Nonnull String ollamaUrl);

    /**
     * Gets the default configuration values as a Map.
     *
     * @return map of default configuration values
     */
    @Nonnull
    Map<String, Object> getDefaultConfiguration();

    /**
     * Lists repository-specific configuration overrides.
     *
     * @return list of overrides (one per repository)
     */
    @Nonnull
    List<Map<String, Object>> listRepositoryConfigurations();

    /**
     * Retrieves the repository-specific configuration details, including effective values.
     *
     * @param projectKey the Bitbucket project key
     * @param repositorySlug the repository slug
     * @return map containing effective configuration, overrides, and defaults
     */
    @Nonnull
    Map<String, Object> getRepositoryConfiguration(@Nonnull String projectKey, @Nonnull String repositorySlug);

    /**
     * Retrieves the effective configuration for a repository after applying overrides.
     *
     * @param projectKey the Bitbucket project key
     * @param repositorySlug the repository slug
     * @return map of effective configuration values
     */
    @Nonnull
    Map<String, Object> getEffectiveConfiguration(@Nonnull String projectKey, @Nonnull String repositorySlug);

    /**
     * Updates or creates repository-specific configuration overrides.
     *
     * @param projectKey the Bitbucket project key
     * @param repositorySlug the repository slug
     * @param overrides map of override values (partial set of configuration keys)
     * @param updatedBy optional user key making the change
     */
    void updateRepositoryConfiguration(@Nonnull String projectKey,
                                       @Nonnull String repositorySlug,
                                       @Nonnull Map<String, Object> overrides,
                                       String updatedBy);

    /**
     * Removes all repository-specific overrides, reverting to global defaults.
     *
     * @param projectKey the Bitbucket project key
     * @param repositorySlug the repository slug
     */
    void clearRepositoryConfiguration(@Nonnull String projectKey, @Nonnull String repositorySlug);

    /**
     * Enumerates all Bitbucket projects and repositories that can be targeted by configuration overrides.
     *
     * @return ordered list of project descriptors (each containing repository descriptors)
     */
    @Nonnull
    List<Map<String, Object>> listRepositoryCatalog();

    /**
     * Synchronizes repository overrides so that only the provided repositories retain a copy of the
     * current configuration. Existing overrides not present in {@code desiredRepositories} are removed,
     * and repositories in the collection are (re)created with the current configuration values.
     *
     * @param desiredRepositories collection of repository coordinates to keep
     * @param updatedBy optional user key performing the change (used for auditing metadata)
     */
    void synchronizeRepositoryOverrides(@Nonnull Collection<RepositoryScope> desiredRepositories,
                                        String updatedBy);

    /**
     * Value object representing a repository address (project key + repository slug).
     */
    final class RepositoryScope {
        private final String projectKey;
        private final String repositorySlug;

        public RepositoryScope(@Nonnull String projectKey, @Nonnull String repositorySlug) {
            this.projectKey = projectKey;
            this.repositorySlug = repositorySlug;
        }

        @Nonnull
        public String getProjectKey() {
            return projectKey;
        }

        @Nonnull
        public String getRepositorySlug() {
            return repositorySlug;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof RepositoryScope)) {
                return false;
            }
            RepositoryScope that = (RepositoryScope) o;
            return projectKey.equals(that.projectKey) && repositorySlug.equals(that.repositorySlug);
        }

        @Override
        public int hashCode() {
            int result = projectKey.hashCode();
            result = 31 * result + repositorySlug.hashCode();
            return result;
        }

        @Override
        public String toString() {
            return projectKey + "/" + repositorySlug;
        }
    }
}
