package com.teknolojikpanda.bitbucket.aireviewer.service;

import java.util.Locale;

public enum GuardrailsRateLimitScope {
    GLOBAL,
    PROJECT,
    REPOSITORY;

    public String key() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static GuardrailsRateLimitScope fromString(String raw) {
        if (raw == null) {
            return REPOSITORY;
        }
        try {
            return GuardrailsRateLimitScope.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return REPOSITORY;
        }
    }
}
