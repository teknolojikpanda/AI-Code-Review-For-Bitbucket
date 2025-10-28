package com.example.bitbucket.aicode.api;

import com.example.bitbucket.aicode.model.ReviewChunk;
import com.example.bitbucket.aicode.model.ReviewContext;

import javax.annotation.Nonnull;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Strategy interface that encapsulates how review chunks are chosen and assembled.
 * <p>
 * This abstraction allows the chunk planner to swap between heuristics
 * (size-based, risk-based, semantic, etc.) without rewriting orchestration code.
 */
public interface ChunkStrategy {

    /**
     * Plan chunk boundaries for the supplied diff.
     *
     * @param context        immutable snapshot of the review inputs
     * @param combinedDiff   full unified diff used for chunking (never {@code null})
     * @param candidateFiles ordered set of files that passed filtering
     * @param metrics        recorder for strategy-specific instrumentation
     * @return planning result describing generated chunks and any truncation metadata
     */
    @Nonnull
    Result plan(@Nonnull ReviewContext context,
                @Nonnull String combinedDiff,
                @Nonnull Set<String> candidateFiles,
                @Nonnull MetricsRecorder metrics);

    /**
     * Planning outcome returned by strategies.
     */
    final class Result {
        private final List<ReviewChunk> chunks;
        private final boolean truncated;
        private final List<String> skippedFiles;

        public Result(@Nonnull List<ReviewChunk> chunks,
                      boolean truncated,
                      @Nonnull List<String> skippedFiles) {
            this.chunks = Collections.unmodifiableList(
                    Objects.requireNonNull(chunks, "chunks"));
            this.truncated = truncated;
            this.skippedFiles = Collections.unmodifiableList(
                    Objects.requireNonNull(skippedFiles, "skippedFiles"));
        }

        /**
         * @return immutable list of generated review chunks
         */
        @Nonnull
        public List<ReviewChunk> getChunks() {
            return chunks;
        }

        /**
         * @return {@code true} if the strategy had to truncate chunks due to limits
         */
        public boolean isTruncated() {
            return truncated;
        }

        /**
         * @return immutable list of files that were skipped (e.g., no hunks, empty content)
         */
        @Nonnull
        public List<String> getSkippedFiles() {
            return skippedFiles;
        }
    }
}
