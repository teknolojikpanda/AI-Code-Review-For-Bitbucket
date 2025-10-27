package com.example.bitbucket.aicode.model;

import javax.annotation.Nonnull;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Result of evaluating a single chunk via the AI client.
 */
public final class ChunkReviewResult {

    private final ReviewChunk chunk;
    private final List<ReviewFinding> findings;
    private final boolean success;
    private final String error;

    private ChunkReviewResult(Builder builder) {
        this.chunk = Objects.requireNonNull(builder.chunk, "chunk");
        this.findings = Collections.unmodifiableList(builder.findings);
        this.success = builder.success;
        this.error = builder.error;
    }

    @Nonnull
    public ReviewChunk getChunk() {
        return chunk;
    }

    @Nonnull
    public List<ReviewFinding> getFindings() {
        return findings;
    }

    public boolean isSuccess() {
        return success;
    }

    public String getError() {
        return error;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private ReviewChunk chunk;
        private List<ReviewFinding> findings = new java.util.ArrayList<>();
        private boolean success = true;
        private String error;

        public Builder chunk(@Nonnull ReviewChunk value) {
            this.chunk = Objects.requireNonNull(value, "value");
            return this;
        }

        public Builder findings(@Nonnull List<ReviewFinding> value) {
            this.findings = new java.util.ArrayList<>(Objects.requireNonNull(value, "value"));
            return this;
        }

        public Builder success(boolean value) {
            this.success = value;
            return this;
        }

        public Builder error(String value) {
            this.error = value;
            return this;
        }

        public ChunkReviewResult build() {
            return new ChunkReviewResult(this);
        }
    }
}
