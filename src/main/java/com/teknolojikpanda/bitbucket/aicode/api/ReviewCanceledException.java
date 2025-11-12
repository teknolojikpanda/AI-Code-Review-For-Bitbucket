package com.teknolojikpanda.bitbucket.aicode.api;

import javax.annotation.Nullable;

/**
 * Signals that an in-progress AI review was canceled by an administrator.
 */
public class ReviewCanceledException extends RuntimeException {

    private final String runId;

    public ReviewCanceledException(String runId, @Nullable String message) {
        super(message != null ? message : "Review canceled by administrator.");
        this.runId = runId;
    }

    public String getRunId() {
        return runId;
    }
}
