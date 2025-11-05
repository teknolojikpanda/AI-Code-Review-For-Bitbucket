package com.teknolojikpanda.bitbucket.aicode.model;

import javax.annotation.Nonnull;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * High level summary of files/hunks included in the review.
 */
public final class ReviewOverview {

    private final Map<String, FileStats> fileStats;
    private final int totalAdditions;
    private final int totalDeletions;
    private final int totalFiles;

    private ReviewOverview(Builder builder) {
        this.fileStats = Collections.unmodifiableMap(builder.fileStats);
        this.totalAdditions = builder.totalAdditions;
        this.totalDeletions = builder.totalDeletions;
        this.totalFiles = builder.totalFiles;
    }

    @Nonnull
    public Map<String, FileStats> getFileStats() {
        return fileStats;
    }

    public int getTotalAdditions() {
        return totalAdditions;
    }

    public int getTotalDeletions() {
        return totalDeletions;
    }

    public int getTotalFiles() {
        return totalFiles;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private final Map<String, FileStats> fileStats = new LinkedHashMap<>();
        private int totalAdditions;
        private int totalDeletions;
        private int totalFiles;

        public Builder addFileStats(@Nonnull String path, @Nonnull FileStats stats) {
            fileStats.put(Objects.requireNonNull(path, "path"), Objects.requireNonNull(stats, "stats"));
            totalAdditions += stats.getAdditions();
            totalDeletions += stats.getDeletions();
            totalFiles = fileStats.size();
            return this;
        }

        public ReviewOverview build() {
            return new ReviewOverview(this);
        }
    }

    public static final class FileStats {
        private final int additions;
        private final int deletions;
        private final boolean binary;

        public FileStats(int additions, int deletions, boolean binary) {
            this.additions = additions;
            this.deletions = deletions;
            this.binary = binary;
        }

        public int getAdditions() {
            return additions;
        }

        public int getDeletions() {
            return deletions;
        }

        public boolean isBinary() {
            return binary;
        }
    }
}
