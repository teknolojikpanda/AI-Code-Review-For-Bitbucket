package com.teknolojikpanda.bitbucket.aicode.model;

import javax.annotation.Nonnull;
import java.util.Collections;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Reviewer preferences regarding severity thresholds and filtering.
 */
public final class ReviewProfile {

    private final SeverityLevel minSeverity;
    private final Set<SeverityLevel> requireApprovalFor;
    private final int maxIssuesPerFile;
    private final boolean skipGeneratedFiles;
    private final boolean reviewTests;

    private ReviewProfile(Builder builder) {
        this.minSeverity = builder.minSeverity;
        this.requireApprovalFor = Collections.unmodifiableSet(new HashSet<>(builder.requireApprovalFor));
        this.maxIssuesPerFile = builder.maxIssuesPerFile;
        this.skipGeneratedFiles = builder.skipGeneratedFiles;
        this.reviewTests = builder.reviewTests;
    }

    @Nonnull
    public SeverityLevel getMinSeverity() {
        return minSeverity;
    }

    @Nonnull
    public Set<SeverityLevel> getRequireApprovalFor() {
        return requireApprovalFor;
    }

    public int getMaxIssuesPerFile() {
        return maxIssuesPerFile;
    }

    public boolean isSkipGeneratedFiles() {
        return skipGeneratedFiles;
    }

    public boolean isReviewTests() {
        return reviewTests;
    }

    public boolean requiresApproval(@Nonnull SeverityLevel severity) {
        return requireApprovalFor.contains(Objects.requireNonNull(severity, "severity"));
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private SeverityLevel minSeverity = SeverityLevel.MEDIUM;
        private Set<SeverityLevel> requireApprovalFor = new HashSet<>();
        private int maxIssuesPerFile = 50;
        private boolean skipGeneratedFiles = true;
        private boolean reviewTests = false;

        public Builder minSeverity(@Nonnull SeverityLevel value) {
            this.minSeverity = Objects.requireNonNull(value, "value");
            return this;
        }

        public Builder requireApprovalFor(@Nonnull Set<SeverityLevel> values) {
            this.requireApprovalFor = new HashSet<>(Objects.requireNonNull(values, "values"));
            return this;
        }

        public Builder addApprovalSeverity(@Nonnull SeverityLevel value) {
            this.requireApprovalFor.add(Objects.requireNonNull(value, "value"));
            return this;
        }

        public Builder maxIssuesPerFile(int value) {
            this.maxIssuesPerFile = value;
            return this;
        }

        public Builder skipGeneratedFiles(boolean value) {
            this.skipGeneratedFiles = value;
            return this;
        }

        public Builder reviewTests(boolean value) {
            this.reviewTests = value;
            return this;
        }

        public ReviewProfile build() {
            return new ReviewProfile(this);
        }
    }
}
