package com.teknolojikpanda.bitbucket.aicode.api;

import com.atlassian.bitbucket.pull.PullRequest;
import com.teknolojikpanda.bitbucket.aicode.model.ReviewConfig;
import com.teknolojikpanda.bitbucket.aicode.model.ReviewContext;

import javax.annotation.Nonnull;

/**
 * Collects diff data and metadata for a pull request.
 */
public interface DiffProvider {

    @Nonnull
    ReviewContext collect(@Nonnull PullRequest pullRequest,
                          @Nonnull ReviewConfig config,
                          @Nonnull MetricsRecorder metrics);
}
