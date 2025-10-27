package com.example.bitbucket.aicode.model;

import javax.annotation.Nonnull;
import java.util.Locale;

/**
 * Supported severity levels. Ordering matters for threshold checks.
 */
public enum SeverityLevel {
    CRITICAL,
    HIGH,
    MEDIUM,
    LOW;

    @Nonnull
    public static SeverityLevel fromString(@Nonnull String value) {
        switch (value.toLowerCase(Locale.ENGLISH)) {
            case "critical":
            case "blocker":
                return CRITICAL;
            case "high":
            case "major":
                return HIGH;
            case "low":
            case "minor":
            case "info":
                return LOW;
            case "medium":
            case "moderate":
            default:
                return MEDIUM;
        }
    }

    public boolean isAtLeast(@Nonnull SeverityLevel threshold) {
        return this.ordinal() <= threshold.ordinal();
    }
}
