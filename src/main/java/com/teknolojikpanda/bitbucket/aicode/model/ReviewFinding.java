package com.teknolojikpanda.bitbucket.aicode.model;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Objects;
import java.util.UUID;

/**
 * Represents a single issue returned by the AI pipeline.
 */
public final class ReviewFinding {

    private final String id;
    private final String filePath;
    private final LineRange lineRange;
    private final SeverityLevel severity;
    private final IssueCategory category;
    private final String summary;
    private final String details;
    private final String fix;
    private final String snippet;

    private ReviewFinding(Builder builder) {
        this.id = builder.id != null ? builder.id : UUID.randomUUID().toString();
        this.filePath = Objects.requireNonNull(builder.filePath, "filePath");
        this.lineRange = builder.lineRange;
        this.severity = Objects.requireNonNull(builder.severity, "severity");
        this.category = Objects.requireNonNull(builder.category, "category");
        this.summary = Objects.requireNonNull(builder.summary, "summary");
        this.details = builder.details;
        this.fix = builder.fix;
        this.snippet = builder.snippet;
    }

    @Nonnull
    public String getId() {
        return id;
    }

    @Nonnull
    public String getFilePath() {
        return filePath;
    }

    @Nullable
    public LineRange getLineRange() {
        return lineRange;
    }

    @Nonnull
    public SeverityLevel getSeverity() {
        return severity;
    }

    @Nonnull
    public IssueCategory getCategory() {
        return category;
    }

    @Nonnull
    public String getSummary() {
        return summary;
    }

    @Nullable
    public String getDetails() {
        return details;
    }

    @Nullable
    public String getFix() {
        return fix;
    }

    @Nullable
    public String getSnippet() {
        return snippet;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String id;
        private String filePath;
        private LineRange lineRange;
        private SeverityLevel severity = SeverityLevel.MEDIUM;
        private IssueCategory category = IssueCategory.OTHER;
        private String summary;
        private String details;
        private String fix;
        private String snippet;

        public Builder id(@Nonnull String value) {
            this.id = Objects.requireNonNull(value, "value");
            return this;
        }

        public Builder filePath(@Nonnull String value) {
            this.filePath = Objects.requireNonNull(value, "value");
            return this;
        }

        public Builder lineRange(@Nullable LineRange value) {
            this.lineRange = value;
            return this;
        }

        public Builder severity(@Nonnull SeverityLevel value) {
            this.severity = Objects.requireNonNull(value, "value");
            return this;
        }

        public Builder category(@Nonnull IssueCategory value) {
            this.category = Objects.requireNonNull(value, "value");
            return this;
        }

        public Builder summary(@Nonnull String value) {
            this.summary = Objects.requireNonNull(value, "value");
            return this;
        }

        public Builder details(@Nullable String value) {
            this.details = value;
            return this;
        }

        public Builder fix(@Nullable String value) {
            this.fix = value;
            return this;
        }

        public Builder snippet(@Nullable String value) {
            this.snippet = value;
            return this;
        }

        public ReviewFinding build() {
            return new ReviewFinding(this);
        }
    }
}
