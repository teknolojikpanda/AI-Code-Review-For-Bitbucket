package com.teknolojikpanda.bitbucket.aicode.api;

import com.teknolojikpanda.bitbucket.aicode.model.ChunkReviewResult;
import com.teknolojikpanda.bitbucket.aicode.model.ReviewChunk;
import com.teknolojikpanda.bitbucket.aicode.model.ReviewContext;
import com.teknolojikpanda.bitbucket.aicode.model.ReviewPreparation;

import javax.annotation.Nonnull;

/**
 * Talks to the underlying LLM to obtain overview and chunk findings.
 */
public interface AiReviewClient {

    @Nonnull
    String generateOverview(@Nonnull ReviewPreparation preparation, @Nonnull MetricsRecorder metrics);

    @Nonnull
    ChunkReviewResult reviewChunk(@Nonnull ReviewChunk chunk,
                                  @Nonnull String overview,
                                  @Nonnull ReviewContext context,
                                  @Nonnull MetricsRecorder metrics);
}
