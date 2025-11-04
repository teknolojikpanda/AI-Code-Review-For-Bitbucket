package com.example.bitbucket.aireviewer.feature;

import javax.annotation.Nullable;

/**
 * Centralised feature flag access for the AI Reviewer plugin.
 *
 * Flags are sourced from JVM system properties first, followed by environment variables,
 * and default to enabled unless explicitly disabled.
 */
public final class FeatureFlags {

    private static final String PROGRESS_ITERATION2_PROPERTY = "ai.reviewer.progress.iteration2.enabled";

    private FeatureFlags() {
    }

    /**
     * Determines whether the Iteration 2 progress experience should be activated.
     *
     * @return true if the feature is enabled (default), false otherwise
     */
    public static boolean isProgressIteration2Enabled() {
        String value = resolveFlag(PROGRESS_ITERATION2_PROPERTY);
        if (value == null || value.trim().isEmpty()) {
            return true;
        }
        value = value.trim();
        return !("false".equalsIgnoreCase(value) || "0".equals(value));
    }

    @Nullable
    private static String resolveFlag(String propertyName) {
        String value = System.getProperty(propertyName);
        if (value != null) {
            return value;
        }
        String envName = propertyName.replace('.', '_').toUpperCase();
        return System.getenv(envName);
    }
}
