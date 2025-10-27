package com.example.bitbucket.aicode.model;

import javax.annotation.Nonnull;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Prepared data ready for AI processing (overview + chunks).
 */
public final class ReviewPreparation {

    private final ReviewContext context;
    private final ReviewOverview overview;
    private final List<ReviewChunk> chunks;
    private final boolean truncated;
    private final List<String> skippedFiles;

    private ReviewPreparation(Builder builder) {
        this.context = Objects.requireNonNull(builder.context, "context");
        this.overview = Objects.requireNonNull(builder.overview, "overview");
        this.chunks = Collections.unmodifiableList(builder.chunks);
        this.truncated = builder.truncated;
        this.skippedFiles = Collections.unmodifiableList(builder.skippedFiles);
    }

    @Nonnull
    public ReviewContext getContext() {
        return context;
    }

    @Nonnull
    public ReviewOverview getOverview() {
        return overview;
    }

    @Nonnull
    public List<ReviewChunk> getChunks() {
        return chunks;
    }

    public boolean isTruncated() {
        return truncated;
    }

    @Nonnull
    public List<String> getSkippedFiles() {
        return skippedFiles;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private ReviewContext context;
        private ReviewOverview overview;
        private List<ReviewChunk> chunks = new java.util.ArrayList<>();
        private boolean truncated;
        private List<String> skippedFiles = new java.util.ArrayList<>();

        public Builder context(@Nonnull ReviewContext value) {
            this.context = Objects.requireNonNull(value, "value");
            return this;
        }

        public Builder overview(@Nonnull ReviewOverview value) {
            this.overview = Objects.requireNonNull(value, "value");
            return this;
        }

        public Builder chunks(@Nonnull List<ReviewChunk> value) {
            this.chunks = new java.util.ArrayList<>(Objects.requireNonNull(value, "value"));
            return this;
        }

        public Builder addChunk(@Nonnull ReviewChunk chunk) {
            this.chunks.add(Objects.requireNonNull(chunk, "chunk"));
            return this;
        }

        public Builder truncated(boolean value) {
            this.truncated = value;
            return this;
        }

        public Builder skippedFiles(@Nonnull List<String> value) {
            this.skippedFiles = new java.util.ArrayList<>(Objects.requireNonNull(value, "value"));
            return this;
        }

        public ReviewPreparation build() {
            return new ReviewPreparation(this);
        }
    }
}
