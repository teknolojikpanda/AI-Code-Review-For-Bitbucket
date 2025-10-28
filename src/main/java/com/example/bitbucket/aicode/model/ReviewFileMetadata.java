package com.example.bitbucket.aicode.model;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Objects;

/**
 * Per-file metadata used by chunk strategies and risk weighting.
 */
public final class ReviewFileMetadata {

    private final String path;
    private final String directory;
    private final String extension;
    private final String language;
    private final boolean binary;
    private final int additions;
    private final int deletions;
    private final boolean testFile;

    private ReviewFileMetadata(Builder builder) {
        this.path = Objects.requireNonNull(builder.path, "path");
        this.directory = builder.directory;
        this.extension = builder.extension;
        this.language = builder.language;
        this.binary = builder.binary;
        this.additions = builder.additions;
        this.deletions = builder.deletions;
        this.testFile = builder.testFile;
    }

    @Nonnull
    public String getPath() {
        return path;
    }

    @Nullable
    public String getDirectory() {
        return directory;
    }

    @Nullable
    public String getExtension() {
        return extension;
    }

    @Nullable
    public String getLanguage() {
        return language;
    }

    public boolean isBinary() {
        return binary;
    }

    public int getAdditions() {
        return additions;
    }

    public int getDeletions() {
        return deletions;
    }

    public int getTotalChanges() {
        return additions + deletions;
    }

    public boolean isTestFile() {
        return testFile;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String path;
        private String directory;
        private String extension;
        private String language;
        private boolean binary;
        private int additions;
        private int deletions;
        private boolean testFile;

        public Builder path(@Nonnull String value) {
            this.path = Objects.requireNonNull(value, "value");
            return this;
        }

        public Builder directory(@Nullable String value) {
            this.directory = value;
            return this;
        }

        public Builder extension(@Nullable String value) {
            this.extension = value;
            return this;
        }

        public Builder language(@Nullable String value) {
            this.language = value;
            return this;
        }

        public Builder binary(boolean value) {
            this.binary = value;
            return this;
        }

        public Builder additions(int value) {
            this.additions = Math.max(0, value);
            return this;
        }

        public Builder deletions(int value) {
            this.deletions = Math.max(0, value);
            return this;
        }

        public Builder testFile(boolean value) {
            this.testFile = value;
            return this;
        }

        public ReviewFileMetadata build() {
            return new ReviewFileMetadata(this);
        }
    }
}
