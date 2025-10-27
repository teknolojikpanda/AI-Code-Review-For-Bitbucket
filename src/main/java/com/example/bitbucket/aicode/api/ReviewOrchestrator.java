package com.example.bitbucket.aicode.api;

import com.example.bitbucket.aicode.model.ReviewPreparation;
import com.example.bitbucket.aicode.model.ReviewSummary;

import javax.annotation.Nonnull;

/**
 * Executes the complete two-pass AI review workflow for prepared chunks.
 */
public interface ReviewOrchestrator {

    @Nonnull
    ReviewSummary runReview(@Nonnull ReviewPreparation preparation, @Nonnull MetricsRecorder metrics);
}
