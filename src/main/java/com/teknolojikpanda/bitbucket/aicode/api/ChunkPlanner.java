package com.teknolojikpanda.bitbucket.aicode.api;

import com.teknolojikpanda.bitbucket.aicode.model.ReviewContext;
import com.teknolojikpanda.bitbucket.aicode.model.ReviewPreparation;

import javax.annotation.Nonnull;

/**
 * Splits collected diff content into AI-sized chunks and provides an overview.
 */
public interface ChunkPlanner {

    @Nonnull
    ReviewPreparation prepare(@Nonnull ReviewContext context, @Nonnull MetricsRecorder metrics);
}
