package com.example.bitbucket.aireviewer.dto;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Objects;

/**
 * Represents a code review issue detected by the AI.
 *
 * Contains all information about a potential problem in the code,
 * including location, severity, description, and suggested fix.
 */
public class ReviewIssue {

    /**
     * Severity levels for review issues.
     */
    public enum Severity {
        CRITICAL("critical"),
        HIGH("high"),
        MEDIUM("medium"),
        LOW("low"),
        INFO("info");

        private final String value;

        Severity(String value) {
            this.value = value;
        }

        public String getValue() {
            return value;
        }

        @Nonnull
        public static Severity fromString(@Nonnull String value) {
            for (Severity severity : values()) {
                if (severity.value.equalsIgnoreCase(value)) {
                    return severity;
                }
            }
            return MEDIUM; // Default to medium if unknown
        }
    }

    private final String path;
    private final Integer line;
    private final Severity severity;
    private final String type;
    private final String summary;
    private final String details;
    private final String fix;
    private final String problematicCode;

    private ReviewIssue(Builder builder) {
        this.path = builder.path;
        this.line = builder.line;
        this.severity = builder.severity;
        this.type = builder.type;
        this.summary = builder.summary;
        this.details = builder.details;
        this.fix = builder.fix;
        this.problematicCode = builder.problematicCode;
    }

    /**
     * Gets the file path relative to repository root.
     *
     * @return the file path
     */
    @Nonnull
    public String getPath() {
        return path;
    }

    /**
     * Gets the line number where the issue occurs.
     *
     * @return the line number, or null if not line-specific
     */
    @Nullable
    public Integer getLine() {
        return line;
    }

    /**
     * Gets the severity level of this issue.
     *
     * @return the severity
     */
    @Nonnull
    public Severity getSeverity() {
        return severity;
    }

    /**
     * Gets the type/category of this issue.
     *
     * Examples: "security", "performance", "bug", "style", "best-practice"
     *
     * @return the issue type
     */
    @Nonnull
    public String getType() {
        return type;
    }

    /**
     * Gets a brief summary of the issue (one line).
     *
     * @return the summary
     */
    @Nonnull
    public String getSummary() {
        return summary;
    }

    /**
     * Gets a detailed explanation of the issue.
     *
     * @return the detailed explanation, or null if not available
     */
    @Nullable
    public String getDetails() {
        return details;
    }

    /**
     * Gets a suggested fix for the issue.
     *
     * @return the suggested fix, or null if not available
     */
    @Nullable
    public String getFix() {
        return fix;
    }

    /**
     * Gets the problematic code snippet.
     *
     * @return the code snippet, or null if not available
     */
    @Nullable
    public String getProblematicCode() {
        return problematicCode;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ReviewIssue that = (ReviewIssue) o;
        return Objects.equals(path, that.path) &&
                Objects.equals(line, that.line) &&
                severity == that.severity &&
                Objects.equals(type, that.type) &&
                Objects.equals(summary, that.summary);
    }

    @Override
    public int hashCode() {
        return Objects.hash(path, line, severity, type, summary);
    }

    @Override
    public String toString() {
        return String.format("[%s] %s:%s - %s: %s",
                severity.getValue(),
                path,
                line != null ? line : "?",
                type,
                summary);
    }

    /**
     * Creates a new builder instance.
     *
     * @return a new builder
     */
    @Nonnull
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder for ReviewIssue.
     */
    public static class Builder {
        private String path;
        private Integer line;
        private Severity severity = Severity.MEDIUM;
        private String type = "general";
        private String summary;
        private String details;
        private String fix;
        private String problematicCode;

        private Builder() {
        }

        @Nonnull
        public Builder path(@Nonnull String path) {
            this.path = path;
            return this;
        }

        @Nonnull
        public Builder line(@Nullable Integer line) {
            this.line = line;
            return this;
        }

        @Nonnull
        public Builder severity(@Nonnull Severity severity) {
            this.severity = severity;
            return this;
        }

        @Nonnull
        public Builder severity(@Nonnull String severity) {
            this.severity = Severity.fromString(severity);
            return this;
        }

        @Nonnull
        public Builder type(@Nonnull String type) {
            this.type = type;
            return this;
        }

        @Nonnull
        public Builder summary(@Nonnull String summary) {
            this.summary = summary;
            return this;
        }

        @Nonnull
        public Builder details(@Nullable String details) {
            this.details = details;
            return this;
        }

        @Nonnull
        public Builder fix(@Nullable String fix) {
            this.fix = fix;
            return this;
        }

        @Nonnull
        public Builder problematicCode(@Nullable String problematicCode) {
            this.problematicCode = problematicCode;
            return this;
        }

        @Nonnull
        public ReviewIssue build() {
            Objects.requireNonNull(path, "path cannot be null");
            Objects.requireNonNull(summary, "summary cannot be null");
            return new ReviewIssue(this);
        }
    }
}
