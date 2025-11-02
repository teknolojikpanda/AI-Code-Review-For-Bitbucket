package com.example.bitbucket.aireviewer.service;

import com.atlassian.bitbucket.pull.PullRequest;
import com.example.bitbucket.aireviewer.dto.ReviewResult;

import javax.annotation.Nonnull;

/**
 * Service interface for AI-powered code review operations.
 *
 * Provides methods for:
 * - Reviewing pull requests
 * - Re-reviewing after updates
 * - Manual review triggers
 * - Review history management
 */
public interface AIReviewService {

    /**
     * Reviews a pull request and posts comments with findings.
     *
     * This method:
     * 1. Fetches the PR diff
     * 2. Validates PR size against limits
     * 3. Filters files based on configuration
     * 4. Chunks the diff for processing
     * 5. Sends chunks to Ollama for analysis
     * 6. Processes AI responses and extracts issues
     * 7. Posts issues as PR comments
     * 8. Builds and posts summary comment
     * 9. Approves or requests changes based on findings
     * 10. Saves review history
     *
     * @param pullRequest the pull request to review
     * @return the review result containing issues and metrics
     */
    @Nonnull
    ReviewResult reviewPullRequest(@Nonnull PullRequest pullRequest);

    /**
     * Re-reviews a pull request after updates.
     *
     * This method:
     * 1. Retrieves previous review results
     * 2. Reviews the updated PR
     * 3. Compares new issues with previous issues
     * 4. Marks resolved issues
     * 5. Posts comments only for new issues
     * 6. Updates the summary comment
     *
     * @param pullRequest the pull request to re-review
     * @return the review result for the update
     */
    @Nonnull
    ReviewResult reReviewPullRequest(@Nonnull PullRequest pullRequest);

    /**
     * Re-reviews a pull request and optionally bypasses duplicate-commit checks.
     *
     * @param pullRequest the pull request to re-review
     * @param force when true, skip duplicate commit detection and run regardless
     * @return review result
     */
    @Nonnull
    ReviewResult reReviewPullRequest(@Nonnull PullRequest pullRequest, boolean force);

    /**
     * Manually triggers a review for a pull request.
     *
     * @param pullRequest the pull request
     * @param force when true, bypass duplicate commit detection and cached results
     * @return review result
     */
    @Nonnull
    ReviewResult manualReview(@Nonnull PullRequest pullRequest, boolean force, boolean treatAsUpdate);

    /**
     * Tests connectivity and functionality of the Ollama service.
     *
     * Sends a simple test prompt to Ollama and validates the response.
     *
     * @return true if Ollama is accessible and responding correctly
     */
    boolean testOllamaConnection();

    /**
     * Gets a detailed explanation for a specific code issue.
     *
     * Can be triggered when a user requests more details about an issue.
     *
     * @param issueId the ID of the issue
     * @return detailed explanation from the AI
     */
    @Nonnull
    String getDetailedExplanation(@Nonnull String issueId);
}
