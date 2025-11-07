package com.teknolojikpanda.bitbucket.aicode.api;

import com.teknolojikpanda.bitbucket.aicode.model.ReviewPreparation;
import com.teknolojikpanda.bitbucket.aicode.model.ReviewSummary;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Executes the complete two-pass AI review workflow for prepared chunks.
 */
public interface ReviewOrchestrator {

    /**
     * Runs the two-pass review without chunk-level callbacks.
     */
    @Nonnull
    default ReviewSummary runReview(@Nonnull ReviewPreparation preparation,
                                    @Nonnull MetricsRecorder metrics) {
        return runReview(preparation, metrics, null);
    }

    /**
     * Runs the two-pass review while reporting chunk lifecycle events.
     *
     * @param preparation  prepared chunks
     * @param metrics      metrics sink
     * @param chunkListener optional chunk listener (may be null)
     */
    @Nonnull
    ReviewSummary runReview(@Nonnull ReviewPreparation preparation,
                            @Nonnull MetricsRecorder metrics,
                            @Nullable ChunkProgressListener chunkListener);
}
