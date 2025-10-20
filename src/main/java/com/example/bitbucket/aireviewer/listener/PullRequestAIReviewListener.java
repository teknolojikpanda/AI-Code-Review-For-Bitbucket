package com.example.bitbucket.aireviewer.listener;

import com.atlassian.bitbucket.event.pull.PullRequestOpenedEvent;
import com.atlassian.bitbucket.event.pull.PullRequestRescopedEvent;
import com.atlassian.bitbucket.pull.PullRequest;
import com.atlassian.event.api.EventListener;
import com.atlassian.event.api.EventPublisher;
import com.atlassian.plugin.spring.scanner.annotation.imports.ComponentImport;
import com.example.bitbucket.aireviewer.dto.ReviewResult;
import com.example.bitbucket.aireviewer.service.AIReviewService;
import com.example.bitbucket.aireviewer.service.AIReviewerConfigService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;

import javax.annotation.Nonnull;
import javax.inject.Inject;
import javax.inject.Named;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Event listener for pull request events that triggers AI code reviews.
 *
 * Listens to:
 * - PullRequestOpenedEvent - When a new PR is created
 * - PullRequestRescopedEvent - When a PR is updated (new commits pushed)
 *
 * Reviews are executed asynchronously to avoid blocking the PR creation/update.
 */
@Named
public class PullRequestAIReviewListener implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(PullRequestAIReviewListener.class);

    private final EventPublisher eventPublisher;
    private final AIReviewService reviewService;
    private final AIReviewerConfigService configService;
    private final ExecutorService executorService;

    @Inject
    public PullRequestAIReviewListener(
            @ComponentImport EventPublisher eventPublisher,
            AIReviewService reviewService,
            AIReviewerConfigService configService) {
        this.eventPublisher = Objects.requireNonNull(eventPublisher, "eventPublisher cannot be null");
        this.reviewService = Objects.requireNonNull(reviewService, "reviewService cannot be null");
        this.configService = Objects.requireNonNull(configService, "configService cannot be null");
        this.executorService = Executors.newFixedThreadPool(2); // Max 2 concurrent reviews

        // Register this listener with the event publisher
        eventPublisher.register(this);
        log.info("PullRequestAIReviewListener registered successfully");
    }

    /**
     * Handles pull request opened events.
     *
     * Triggered when a new PR is created.
     *
     * @param event the PR opened event
     */
    @EventListener
    public void onPullRequestOpened(@Nonnull PullRequestOpenedEvent event) {
        PullRequest pullRequest = event.getPullRequest();
        log.info("PR opened event received: PR #{} in {}/{}",
                pullRequest.getId(),
                pullRequest.getToRef().getRepository().getProject().getKey(),
                pullRequest.getToRef().getRepository().getSlug());

        // Check if reviews are enabled
        if (!isReviewEnabled()) {
            log.info("AI reviews are disabled in configuration - skipping PR #{}", pullRequest.getId());
            return;
        }

        // Check if this is a draft PR
        if (isDraftPR(pullRequest)) {
            boolean reviewDrafts = shouldReviewDraftPRs();
            if (!reviewDrafts) {
                log.info("PR #{} is a draft and reviewDraftPRs=false - skipping", pullRequest.getId());
                return;
            }
            log.info("PR #{} is a draft but reviewDraftPRs=true - proceeding with review", pullRequest.getId());
        }

        // Execute review asynchronously
        executeReviewAsync(pullRequest, false);
    }

    /**
     * Handles pull request rescoped events.
     *
     * Triggered when new commits are pushed to an existing PR.
     *
     * @param event the PR rescoped event
     */
    @EventListener
    public void onPullRequestRescoped(@Nonnull PullRequestRescopedEvent event) {
        PullRequest pullRequest = event.getPullRequest();
        log.info("PR rescoped event received: PR #{} in {}/{}",
                pullRequest.getId(),
                pullRequest.getToRef().getRepository().getProject().getKey(),
                pullRequest.getToRef().getRepository().getSlug());

        // Check if reviews are enabled
        if (!isReviewEnabled()) {
            log.info("AI reviews are disabled in configuration - skipping PR #{}", pullRequest.getId());
            return;
        }

        // Check if this is a draft PR
        if (isDraftPR(pullRequest)) {
            boolean reviewDrafts = shouldReviewDraftPRs();
            if (!reviewDrafts) {
                log.info("PR #{} is a draft and reviewDraftPRs=false - skipping", pullRequest.getId());
                return;
            }
        }

        // Execute re-review asynchronously (compares with previous review)
        executeReviewAsync(pullRequest, true);
    }

    /**
     * Executes a review asynchronously in a background thread.
     *
     * @param pullRequest the pull request to review
     * @param isUpdate true if this is a re-review (PR update), false if new PR
     */
    private void executeReviewAsync(@Nonnull PullRequest pullRequest, boolean isUpdate) {
        executorService.submit(() -> {
            try {
                log.info("Starting {} review for PR #{} (async)",
                        isUpdate ? "update" : "initial",
                        pullRequest.getId());

                ReviewResult result;
                if (isUpdate) {
                    result = reviewService.reReviewPullRequest(pullRequest);
                } else {
                    result = reviewService.reviewPullRequest(pullRequest);
                }

                log.info("Review completed for PR #{}: status={}, issues={}, filesReviewed={}",
                        pullRequest.getId(),
                        result.getStatus(),
                        result.getIssueCount(),
                        result.getFilesReviewed());

                if (result.hasCriticalIssues()) {
                    log.warn("PR #{} has critical issues that require attention", pullRequest.getId());
                }

            } catch (Exception e) {
                log.error("Failed to review PR #{}: {}",
                        pullRequest.getId(),
                        e.getMessage(),
                        e);
            }
        });
    }

    /**
     * Checks if AI reviews are enabled in the configuration.
     *
     * @return true if enabled, false otherwise
     */
    private boolean isReviewEnabled() {
        try {
            Map<String, Object> config = configService.getConfigurationAsMap();
            Object enabled = config.get("enabled");
            if (enabled instanceof Boolean) {
                return (Boolean) enabled;
            }
            return true; // Default to enabled if not configured
        } catch (Exception e) {
            log.error("Failed to check if reviews are enabled - defaulting to true", e);
            return true;
        }
    }

    /**
     * Checks if draft PRs should be reviewed.
     *
     * @return true if draft PRs should be reviewed, false otherwise
     */
    private boolean shouldReviewDraftPRs() {
        try {
            Map<String, Object> config = configService.getConfigurationAsMap();
            Object reviewDrafts = config.get("reviewDraftPRs");
            if (reviewDrafts instanceof Boolean) {
                return (Boolean) reviewDrafts;
            }
            return false; // Default to not reviewing drafts
        } catch (Exception e) {
            log.error("Failed to check reviewDraftPRs setting - defaulting to false", e);
            return false;
        }
    }

    /**
     * Checks if a pull request is a draft.
     *
     * Note: Bitbucket Data Center 8.9.0 may not have native draft PR support.
     * This implementation checks for common draft indicators:
     * - Title starting with "WIP:", "Draft:", "[WIP]", "[Draft]"
     * - Description containing draft markers
     *
     * @param pullRequest the pull request to check
     * @return true if the PR appears to be a draft
     */
    private boolean isDraftPR(@Nonnull PullRequest pullRequest) {
        String title = pullRequest.getTitle();
        if (title != null) {
            String titleLower = title.toLowerCase();
            if (titleLower.startsWith("wip:") ||
                    titleLower.startsWith("draft:") ||
                    titleLower.startsWith("[wip]") ||
                    titleLower.startsWith("[draft]") ||
                    titleLower.contains("[wip]") ||
                    titleLower.contains("[draft]")) {
                return true;
            }
        }

        // Could also check description, but that might be too aggressive
        // Uncomment if needed:
        /*
        String description = pullRequest.getDescription();
        if (description != null) {
            String descLower = description.toLowerCase();
            if (descLower.contains("work in progress") || descLower.contains("draft pr")) {
                return true;
            }
        }
        */

        return false;
    }

    /**
     * Cleanup method called when the plugin is unloaded.
     * Unregisters the listener and shuts down the executor.
     */
    @Override
    public void destroy() {
        log.info("Destroying PullRequestAIReviewListener");
        eventPublisher.unregister(this);
        executorService.shutdown();
        log.info("PullRequestAIReviewListener destroyed successfully");
    }
}
