package com.teknolojikpanda.bitbucket.aireviewer.dto;

import com.teknolojikpanda.bitbucket.aireviewer.progress.ProgressEvent;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Represents the complete result of an AI code review.
 *
 * Contains all detected issues, metrics, and status information.
 */
public class ReviewResult {

    /**
     * Status of the review process.
     */
    public enum Status {
        SUCCESS("success"),
        PARTIAL("partial"),
        FAILED("failed"),
        CANCELED("canceled"),
        SKIPPED("skipped");

        private final String value;

        Status(String value) {
            this.value = value;
        }

        public String getValue() {
            return value;
        }

        @Nonnull
        public static Status fromString(@Nonnull String value) {
            for (Status status : values()) {
                if (status.value.equalsIgnoreCase(value)) {
                    return status;
                }
            }
            return FAILED; // Default to failed if unknown
        }
    }

    private final List<ReviewIssue> issues;
    private final Map<String, Object> metrics;
    private final Status status;
    private final String message;
    private final long pullRequestId;
    private final int filesReviewed;
    private final int filesSkipped;
    private final List<ProgressEvent> progressEvents;

    private ReviewResult(Builder builder) {
        this.issues = Collections.unmodifiableList(new ArrayList<>(builder.issues));
        this.metrics = Collections.unmodifiableMap(new HashMap<>(builder.metrics));
        this.status = builder.status;
        this.message = builder.message;
        this.pullRequestId = builder.pullRequestId;
        this.filesReviewed = builder.filesReviewed;
        this.filesSkipped = builder.filesSkipped;
        this.progressEvents = Collections.unmodifiableList(new ArrayList<>(builder.progressEvents));
    }

    /**
     * Gets all detected issues.
     *
     * @return unmodifiable list of issues
     */
    @Nonnull
    public List<ReviewIssue> getIssues() {
        return issues;
    }

    /**
     * Gets performance and statistical metrics.
     *
     * @return unmodifiable map of metrics
     */
    @Nonnull
    public Map<String, Object> getMetrics() {
        return metrics;
    }

    /**
     * Gets the overall status of the review.
     *
     * @return the status
     */
    @Nonnull
    public Status getStatus() {
        return status;
    }

    /**
     * Gets a status message (e.g., error message if failed).
     *
     * @return the message, or null if not available
     */
    @Nullable
    public String getMessage() {
        return message;
    }

    /**
     * Gets the pull request ID that was reviewed.
     *
     * @return the PR ID
     */
    public long getPullRequestId() {
        return pullRequestId;
    }

    /**
     * Gets the number of files that were successfully reviewed.
     *
     * @return number of files reviewed
     */
    public int getFilesReviewed() {
        return filesReviewed;
    }

    /**
     * Gets the number of files that were skipped.
     *
     * @return number of files skipped
     */
    public int getFilesSkipped() {
        return filesSkipped;
    }

    /**
     * Gets the recorded progress events for this review.
     *
     * @return unmodifiable list of progress events
     */
    @Nonnull
    public List<ProgressEvent> getProgressEvents() {
        return progressEvents;
    }

    /**
     * Gets issues filtered by severity.
     *
     * @param minSeverity minimum severity level to include
     * @return filtered list of issues
     */
    @Nonnull
    public List<ReviewIssue> getIssuesBySeverity(@Nonnull ReviewIssue.Severity minSeverity) {
        List<ReviewIssue> filtered = new ArrayList<>();
        for (ReviewIssue issue : issues) {
            if (issue.getSeverity().ordinal() <= minSeverity.ordinal()) {
                filtered.add(issue);
            }
        }
        return filtered;
    }

    /**
     * Gets issues for a specific file.
     *
     * @param filePath the file path
     * @return list of issues for that file
     */
    @Nonnull
    public List<ReviewIssue> getIssuesForFile(@Nonnull String filePath) {
        List<ReviewIssue> filtered = new ArrayList<>();
        for (ReviewIssue issue : issues) {
            if (issue.getPath().equals(filePath)) {
                filtered.add(issue);
            }
        }
        return filtered;
    }

    /**
     * Checks if the review was successful.
     *
     * @return true if status is SUCCESS
     */
    public boolean isSuccess() {
        return status == Status.SUCCESS;
    }

    /**
     * Checks if any critical or high severity issues were found.
     *
     * @return true if critical or high severity issues exist
     */
    public boolean hasCriticalIssues() {
        for (ReviewIssue issue : issues) {
            if (issue.getSeverity() == ReviewIssue.Severity.CRITICAL ||
                    issue.getSeverity() == ReviewIssue.Severity.HIGH) {
                return true;
            }
        }
        return false;
    }

    /**
     * Gets the total number of issues.
     *
     * @return issue count
     */
    public int getIssueCount() {
        return issues.size();
    }

    @Override
    public String toString() {
        return String.format("ReviewResult{status=%s, pullRequestId=%d, issues=%d, filesReviewed=%d, filesSkipped=%d}",
                status.getValue(), pullRequestId, issues.size(), filesReviewed, filesSkipped);
    }

    /**
     * Creates a new builder instance.
     *
     * @return a new builder
     */
    @Nonnull
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder for ReviewResult.
     */
    public static class Builder {
        private final List<ReviewIssue> issues = new ArrayList<>();
        private final Map<String, Object> metrics = new HashMap<>();
        private Status status = Status.SUCCESS;
        private String message;
        private long pullRequestId;
        private int filesReviewed = 0;
        private int filesSkipped = 0;
        private final List<ProgressEvent> progressEvents = new ArrayList<>();

        private Builder() {
        }

        @Nonnull
        public Builder addIssue(@Nonnull ReviewIssue issue) {
            this.issues.add(issue);
            return this;
        }

        @Nonnull
        public Builder addIssues(@Nonnull List<ReviewIssue> issues) {
            this.issues.addAll(issues);
            return this;
        }

        @Nonnull
        public Builder issues(@Nonnull List<ReviewIssue> issues) {
            this.issues.clear();
            this.issues.addAll(issues);
            return this;
        }

        @Nonnull
        public Builder addMetric(@Nonnull String key, @Nonnull Object value) {
            this.metrics.put(key, value);
            return this;
        }

        @Nonnull
        public Builder metrics(@Nonnull Map<String, Object> metrics) {
            this.metrics.clear();
            this.metrics.putAll(metrics);
            return this;
        }

        @Nonnull
        public Builder status(@Nonnull Status status) {
            this.status = status;
            return this;
        }

        @Nonnull
        public Builder status(@Nonnull String status) {
            this.status = Status.fromString(status);
            return this;
        }

        @Nonnull
        public Builder message(@Nullable String message) {
            this.message = message;
            return this;
        }

        @Nonnull
        public Builder pullRequestId(long pullRequestId) {
            this.pullRequestId = pullRequestId;
            return this;
        }

        @Nonnull
        public Builder filesReviewed(int filesReviewed) {
            this.filesReviewed = filesReviewed;
            return this;
        }

        @Nonnull
        public Builder filesSkipped(int filesSkipped) {
            this.filesSkipped = filesSkipped;
            return this;
        }

        @Nonnull
        public Builder progressEvents(@Nonnull List<ProgressEvent> events) {
            this.progressEvents.clear();
            this.progressEvents.addAll(events);
            return this;
        }

        @Nonnull
        public Builder addProgressEvent(@Nonnull ProgressEvent event) {
            this.progressEvents.add(event);
            return this;
        }

        @Nonnull
        public ReviewResult build() {
            return new ReviewResult(this);
        }
    }
}
