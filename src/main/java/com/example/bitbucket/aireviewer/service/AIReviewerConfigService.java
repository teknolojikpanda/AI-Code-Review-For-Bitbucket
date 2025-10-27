package com.example.bitbucket.aireviewer.service;

import com.example.bitbucket.aireviewer.ao.AIReviewConfiguration;

import javax.annotation.Nonnull;
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
}
