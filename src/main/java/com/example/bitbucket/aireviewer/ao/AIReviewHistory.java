package com.example.bitbucket.aireviewer.ao;

import net.java.ao.Entity;
import net.java.ao.Preload;
import net.java.ao.schema.Indexed;
import net.java.ao.schema.NotNull;
import net.java.ao.OneToMany;
import net.java.ao.schema.StringLength;
import net.java.ao.schema.Table;

/**
 * Active Objects entity for storing AI review history
 */
@Preload
@Table("AI_REVIEW_HISTORY")
public interface AIReviewHistory extends Entity {

    // Pull Request Information
    @NotNull
    @Indexed
    long getPullRequestId();
    void setPullRequestId(long prId);

    @NotNull
    @StringLength(255)
    @Indexed
    String getProjectKey();
    void setProjectKey(String projectKey);

    @NotNull
    @StringLength(255)
    @Indexed
    String getRepositorySlug();
    void setRepositorySlug(String slug);

    @StringLength(255)
    String getCommitId();
    void setCommitId(String commitId);

    // Review Execution Information
    @NotNull
    long getReviewStartTime();
    void setReviewStartTime(long timestamp);

    long getReviewEndTime();
    void setReviewEndTime(long timestamp);

    @NotNull
    @StringLength(50)
    String getReviewStatus();
    void setReviewStatus(String status); // PENDING, IN_PROGRESS, COMPLETED, FAILED

    @StringLength(255)
    String getModelUsed();
    void setModelUsed(String model);

    // Analysis Results
    int getTotalIssuesFound();
    void setTotalIssuesFound(int count);

    int getCriticalIssues();
    void setCriticalIssues(int count);

    int getHighIssues();
    void setHighIssues(int count);

    int getMediumIssues();
    void setMediumIssues(int count);

    int getLowIssues();
    void setLowIssues(int count);

    // Processing Metrics
    int getTotalChunks();
    void setTotalChunks(int chunks);

    int getSuccessfulChunks();
    void setSuccessfulChunks(int chunks);

    int getFailedChunks();
    void setFailedChunks(int chunks);

    int getTotalFiles();
    void setTotalFiles(int files);

    int getFilesReviewed();
    void setFilesReviewed(int files);

    long getDiffSize();
    void setDiffSize(long size);

    int getLineCount();
    void setLineCount(int lines);

    // Performance Metrics
    double getAnalysisTimeSeconds();
    void setAnalysisTimeSeconds(double seconds);

    int getCommentsPosted();
    void setCommentsPosted(int count);

    @StringLength(64)
    String getProfileKey();
    void setProfileKey(String profileKey);

    boolean isAutoApproveEnabled();
    void setAutoApproveEnabled(boolean enabled);

    int getPrimaryModelInvocations();
    void setPrimaryModelInvocations(int invocations);

    int getPrimaryModelSuccesses();
    void setPrimaryModelSuccesses(int successes);

    int getPrimaryModelFailures();
    void setPrimaryModelFailures(int failures);

    int getFallbackModelInvocations();
    void setFallbackModelInvocations(int invocations);

    int getFallbackModelSuccesses();
    void setFallbackModelSuccesses(int successes);

    int getFallbackModelFailures();
    void setFallbackModelFailures(int failures);

    int getFallbackTriggered();
    void setFallbackTriggered(int triggered);

    // Review Outcome
    @StringLength(50)
    String getReviewOutcome();
    void setReviewOutcome(String outcome); // APPROVED, CHANGES_REQUESTED, SKIPPED

    long getSummaryCommentId();
    void setSummaryCommentId(long commentId);

    // Update Tracking (for rescoped PRs)
    boolean isUpdateReview();
    void setUpdateReview(boolean isUpdate);

    int getPreviousIssuesCount();
    void setPreviousIssuesCount(int count);

    int getResolvedIssuesCount();
    void setResolvedIssuesCount(int count);

    int getNewIssuesCount();
    void setNewIssuesCount(int count);

    // Error Information
    @StringLength(StringLength.UNLIMITED)
    String getErrorMessage();
    void setErrorMessage(String error);

    @StringLength(StringLength.UNLIMITED)
    String getMetricsJson();
    void setMetricsJson(String json);

    // Configuration Used
    @StringLength(StringLength.UNLIMITED)
    String getConfigurationSnapshot();
    void setConfigurationSnapshot(String config);

    @OneToMany
    AIReviewChunk[] getChunks();
}
