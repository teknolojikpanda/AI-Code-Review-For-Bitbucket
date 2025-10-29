package com.example.bitbucket.aicode.model;

import com.atlassian.bitbucket.pull.PullRequest;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.time.Instant;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;

/**
 * Holds collected diff information and configuration prior to chunking.
 */
public final class ReviewContext {

    private final PullRequest pullRequest;
    private final ReviewConfig config;
    private final String rawDiff;
    private final Map<String, ReviewOverview.FileStats> fileStats;
    private final Map<String, String> fileDiffs;
    private final Map<String, ReviewFileMetadata> fileMetadata;
    private final Instant collectedAt;

    private ReviewContext(Builder builder) {
        this.pullRequest = Objects.requireNonNull(builder.pullRequest, "pullRequest");
        this.config = Objects.requireNonNull(builder.config, "config");
        this.rawDiff = builder.rawDiff;
        this.fileStats = builder.fileStats != null
                ? Collections.unmodifiableMap(builder.fileStats)
                : Collections.emptyMap();
        this.fileDiffs = builder.fileDiffs != null
                ? Collections.unmodifiableMap(builder.fileDiffs)
                : Collections.emptyMap();
        this.fileMetadata = builder.fileMetadata != null
                ? Collections.unmodifiableMap(builder.fileMetadata)
                : Collections.emptyMap();
        this.collectedAt = builder.collectedAt != null ? builder.collectedAt : Instant.now();
    }

    @Nonnull
    public PullRequest getPullRequest() {
        return pullRequest;
    }

    @Nonnull
    public ReviewConfig getConfig() {
        return config;
    }

    @Nullable
    public String getRawDiff() {
        return rawDiff;
    }

    @Nonnull
    public Map<String, ReviewOverview.FileStats> getFileStats() {
        return fileStats;
    }

    @Nonnull
    public Map<String, String> getFileDiffs() {
        return fileDiffs;
    }

    @Nonnull
    public Map<String, ReviewFileMetadata> getFileMetadata() {
        return fileMetadata;
    }

    @Nonnull
    public Instant getCollectedAt() {
        return collectedAt;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private PullRequest pullRequest;
        private ReviewConfig config;
        private String rawDiff;
        private Map<String, ReviewOverview.FileStats> fileStats;
        private Map<String, String> fileDiffs;
        private Map<String, ReviewFileMetadata> fileMetadata;
        private Instant collectedAt;

        public Builder pullRequest(@Nonnull PullRequest value) {
            this.pullRequest = Objects.requireNonNull(value, "value");
            return this;
        }

        public Builder config(@Nonnull ReviewConfig value) {
            this.config = Objects.requireNonNull(value, "value");
            return this;
        }

        public Builder rawDiff(@Nullable String value) {
            this.rawDiff = value;
            return this;
        }

        public Builder fileStats(@Nonnull Map<String, ReviewOverview.FileStats> value) {
            this.fileStats = new java.util.LinkedHashMap<>(Objects.requireNonNull(value, "value"));
            return this;
        }

        public Builder fileDiffs(@Nonnull Map<String, String> value) {
            this.fileDiffs = new java.util.LinkedHashMap<>(Objects.requireNonNull(value, "value"));
            return this;
        }

        public Builder fileMetadata(@Nonnull Map<String, ReviewFileMetadata> value) {
            this.fileMetadata = new java.util.LinkedHashMap<>(Objects.requireNonNull(value, "value"));
            return this;
        }

        public Builder collectedAt(@Nonnull Instant value) {
            this.collectedAt = Objects.requireNonNull(value, "value");
            return this;
        }

        public ReviewContext build() {
            return new ReviewContext(this);
        }
    }
}
