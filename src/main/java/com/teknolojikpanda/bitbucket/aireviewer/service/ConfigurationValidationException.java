package com.teknolojikpanda.bitbucket.aireviewer.service;

import javax.annotation.Nonnull;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Exception thrown when configuration validation fails.
 * Carries a field-to-error message map for returning structured responses.
 */
public class ConfigurationValidationException extends IllegalArgumentException {

    private final Map<String, String> errors;

    public ConfigurationValidationException(@Nonnull Map<String, String> errors) {
        super("Configuration validation failed");
        if (errors.isEmpty()) {
            throw new IllegalArgumentException("Errors map must not be empty");
        }
        this.errors = Collections.unmodifiableMap(new LinkedHashMap<>(errors));
    }

    /**
     * Returns an immutable view of field validation errors.
     */
    @Nonnull
    public Map<String, String> getErrors() {
        return errors;
    }
}
