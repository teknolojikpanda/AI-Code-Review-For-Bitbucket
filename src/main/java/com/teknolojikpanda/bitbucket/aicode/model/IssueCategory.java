package com.teknolojikpanda.bitbucket.aicode.model;

import javax.annotation.Nonnull;
import java.util.Locale;

/**
 * General buckets for classifying AI findings.
 */
public enum IssueCategory {
    SECURITY,
    BUG,
    PERFORMANCE,
    RELIABILITY,
    MAINTAINABILITY,
    STYLE,
    DOCUMENTATION,
    OTHER;

    @Nonnull
    public static IssueCategory fromString(@Nonnull String value) {
        switch (value.toLowerCase(Locale.ENGLISH)) {
            case "security":
            case "vulnerability":
                return SECURITY;
            case "bug":
            case "logic":
            case "correctness":
                return BUG;
            case "performance":
                return PERFORMANCE;
            case "reliability":
            case "stability":
                return RELIABILITY;
            case "maintainability":
            case "maintenance":
                return MAINTAINABILITY;
            case "style":
            case "formatting":
                return STYLE;
            case "documentation":
            case "doc":
                return DOCUMENTATION;
            default:
                return OTHER;
        }
    }
}
