package com.teknolojikpanda.bitbucket.aicode.model;

import javax.annotation.Nonnull;
import java.net.URI;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Snapshot of runtime configuration used for a single review execution.
 */
public final class ReviewConfig {

    private final URI primaryModelEndpoint;
    private final String primaryModel;
    private final URI fallbackModelEndpoint;
    private final String fallbackModel;
    private final int maxCharsPerChunk;
    private final int maxFilesPerChunk;
    private final int maxChunks;
    private final int parallelThreads;
    private final int requestTimeoutMs;
    private final int connectTimeoutMs;
    private final int chunkMaxRetries;
    private final int chunkRetryDelayMs;
    private final int overviewMaxRetries;
    private final int overviewRetryDelayMs;
    private final boolean skipPrimaryModel;
    private final Set<String> reviewableExtensions;
    private final List<String> ignorePatterns;
    private final List<String> ignorePaths;
    private final int maxDiffBytes;
    private final ReviewProfile profile;
    private final PromptTemplates promptTemplates;

    private ReviewConfig(Builder builder) {
        this.primaryModelEndpoint = builder.primaryModelEndpoint;
        this.primaryModel = builder.primaryModel;
        this.fallbackModelEndpoint = builder.fallbackModelEndpoint;
        this.fallbackModel = builder.fallbackModel;
        this.maxCharsPerChunk = builder.maxCharsPerChunk;
        this.maxFilesPerChunk = builder.maxFilesPerChunk;
        this.maxChunks = builder.maxChunks;
        this.parallelThreads = builder.parallelThreads;
        this.requestTimeoutMs = builder.requestTimeoutMs;
        this.connectTimeoutMs = builder.connectTimeoutMs;
        this.chunkMaxRetries = builder.chunkMaxRetries;
        this.chunkRetryDelayMs = builder.chunkRetryDelayMs;
        this.overviewMaxRetries = builder.overviewMaxRetries;
        this.overviewRetryDelayMs = builder.overviewRetryDelayMs;
        this.skipPrimaryModel = builder.skipPrimaryModel;
        this.reviewableExtensions = Collections.unmodifiableSet(builder.reviewableExtensions);
        this.ignorePatterns = Collections.unmodifiableList(builder.ignorePatterns);
        this.ignorePaths = Collections.unmodifiableList(builder.ignorePaths);
        this.maxDiffBytes = builder.maxDiffBytes;
        this.profile = builder.profile;
        this.promptTemplates = builder.promptTemplates;
    }

    @Nonnull
    public URI getPrimaryModelEndpoint() {
        return primaryModelEndpoint;
    }

    @Nonnull
    public String getPrimaryModel() {
        return primaryModel;
    }

    @Nonnull
    public URI getFallbackModelEndpoint() {
        return fallbackModelEndpoint;
    }

    @Nonnull
    public String getFallbackModel() {
        return fallbackModel;
    }

    public int getMaxCharsPerChunk() {
        return maxCharsPerChunk;
    }

    public int getMaxFilesPerChunk() {
        return maxFilesPerChunk;
    }

    public int getMaxChunks() {
        return maxChunks;
    }

    public int getParallelThreads() {
        return parallelThreads;
    }

    public int getRequestTimeoutMs() {
        return requestTimeoutMs;
    }

    public int getConnectTimeoutMs() {
        return connectTimeoutMs;
    }

    public int getMaxRetries() {
        return getChunkMaxRetries();
    }

    public int getBaseRetryDelayMs() {
        return getChunkRetryDelayMs();
    }

    public int getChunkMaxRetries() {
        return chunkMaxRetries;
    }

    public int getChunkRetryDelayMs() {
        return chunkRetryDelayMs;
    }

    public int getOverviewMaxRetries() {
        return overviewMaxRetries;
    }

    public int getOverviewRetryDelayMs() {
        return overviewRetryDelayMs;
    }

    public boolean isSkipPrimaryModel() {
        return skipPrimaryModel;
    }

    @Nonnull
    public Set<String> getReviewableExtensions() {
        return reviewableExtensions;
    }

    @Nonnull
    public List<String> getIgnorePatterns() {
        return ignorePatterns;
    }

    @Nonnull
    public List<String> getIgnorePaths() {
        return ignorePaths;
    }

    public int getMaxDiffBytes() {
        return maxDiffBytes;
    }

    @Nonnull
    public ReviewProfile getProfile() {
        return profile;
    }

    @Nonnull
    public PromptTemplates getPromptTemplates() {
        return promptTemplates;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private URI primaryModelEndpoint;
        private String primaryModel;
        private URI fallbackModelEndpoint;
        private String fallbackModel;
        private int maxCharsPerChunk = 60_000;
        private int maxFilesPerChunk = 3;
        private int maxChunks = 20;
        private int parallelThreads = 4;
        private int requestTimeoutMs = 300_000;
        private int connectTimeoutMs = 15_000;
        private int chunkMaxRetries = 3;
        private int chunkRetryDelayMs = 1_000;
        private int overviewMaxRetries = 2;
        private int overviewRetryDelayMs = 1_500;
        private boolean skipPrimaryModel = false;
        private Set<String> reviewableExtensions = Collections.emptySet();
        private List<String> ignorePatterns = Collections.emptyList();
        private List<String> ignorePaths = Collections.emptyList();
        private int maxDiffBytes = 10_000_000;
        private ReviewProfile profile = ReviewProfile.builder().build();
        private PromptTemplates promptTemplates = PromptTemplates.loadDefaults();

        public Builder primaryModelEndpoint(@Nonnull URI uri) {
            this.primaryModelEndpoint = Objects.requireNonNull(uri, "uri");
            return this;
        }

        public Builder primaryModel(@Nonnull String value) {
            this.primaryModel = Objects.requireNonNull(value, "value");
            return this;
        }

        public Builder fallbackModelEndpoint(@Nonnull URI uri) {
            this.fallbackModelEndpoint = Objects.requireNonNull(uri, "uri");
            return this;
        }

        public Builder fallbackModel(@Nonnull String value) {
            this.fallbackModel = Objects.requireNonNull(value, "value");
            return this;
        }

        public Builder maxCharsPerChunk(int value) {
            this.maxCharsPerChunk = value;
            return this;
        }

        public Builder maxFilesPerChunk(int value) {
            this.maxFilesPerChunk = value;
            return this;
        }

        public Builder maxChunks(int value) {
            this.maxChunks = value;
            return this;
        }

        public Builder parallelThreads(int value) {
            this.parallelThreads = value;
            return this;
        }

        public Builder requestTimeoutMs(int value) {
            this.requestTimeoutMs = value;
            return this;
        }

        public Builder connectTimeoutMs(int value) {
            this.connectTimeoutMs = value;
            return this;
        }

        public Builder chunkMaxRetries(int value) {
            this.chunkMaxRetries = value;
            return this;
        }

        public Builder chunkRetryDelayMs(int value) {
            this.chunkRetryDelayMs = value;
            return this;
        }

        public Builder overviewMaxRetries(int value) {
            this.overviewMaxRetries = value;
            return this;
        }

        public Builder overviewRetryDelayMs(int value) {
            this.overviewRetryDelayMs = value;
            return this;
        }

        public Builder skipPrimaryModel(boolean value) {
            this.skipPrimaryModel = value;
            return this;
        }

        public Builder maxRetries(int value) {
            this.chunkMaxRetries = value;
            if (this.overviewMaxRetries <= 0) {
                this.overviewMaxRetries = value;
            }
            return this;
        }

        public Builder baseRetryDelayMs(int value) {
            this.chunkRetryDelayMs = value;
            if (this.overviewRetryDelayMs <= 0) {
                this.overviewRetryDelayMs = value;
            }
            return this;
        }

        public Builder reviewableExtensions(@Nonnull Set<String> values) {
            this.reviewableExtensions = Collections.unmodifiableSet(new java.util.HashSet<>(Objects.requireNonNull(values, "values")));
            return this;
        }

        public Builder ignorePatterns(@Nonnull List<String> values) {
            this.ignorePatterns = Collections.unmodifiableList(new java.util.ArrayList<>(Objects.requireNonNull(values, "values")));
            return this;
        }

        public Builder ignorePaths(@Nonnull List<String> values) {
            this.ignorePaths = Collections.unmodifiableList(new java.util.ArrayList<>(Objects.requireNonNull(values, "values")));
            return this;
        }

        public Builder maxDiffBytes(int value) {
            this.maxDiffBytes = value;
            return this;
        }

        public Builder profile(@Nonnull ReviewProfile value) {
            this.profile = Objects.requireNonNull(value, "value");
            return this;
        }

        public Builder promptTemplates(@Nonnull PromptTemplates value) {
            this.promptTemplates = Objects.requireNonNull(value, "value");
            return this;
        }

        public ReviewConfig build() {
            Objects.requireNonNull(primaryModelEndpoint, "primaryModelEndpoint");
            Objects.requireNonNull(primaryModel, "primaryModel");
            Objects.requireNonNull(fallbackModelEndpoint, "fallbackModelEndpoint");
            Objects.requireNonNull(fallbackModel, "fallbackModel");
            return new ReviewConfig(this);
        }
    }
}
