package com.example.bitbucket.aicode.api;

import com.example.bitbucket.aicode.model.ChunkReviewResult;
import com.example.bitbucket.aicode.model.ReviewChunk;
import com.example.bitbucket.aicode.model.ReviewContext;
import com.example.bitbucket.aicode.model.ReviewPreparation;

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
