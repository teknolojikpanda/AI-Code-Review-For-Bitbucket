package com.teknolojikpanda.bitbucket.aireviewer.service;

/**
 * Thrown when the concurrency controller rejects a new review request because the
 * queue has reached its configured capacity.
 */
public class ReviewQueueFullException extends RuntimeException {

    private final int maxConcurrent;
    private final int maxQueueSize;
    private final int waitingCount;

    public ReviewQueueFullException(String message,
                                    int maxConcurrent,
                                    int maxQueueSize,
                                    int waitingCount) {
        super(message);
        this.maxConcurrent = maxConcurrent;
        this.maxQueueSize = maxQueueSize;
        this.waitingCount = waitingCount;
    }

    public int getMaxConcurrent() {
        return maxConcurrent;
    }

    public int getMaxQueueSize() {
        return maxQueueSize;
    }

    public int getWaitingCount() {
        return waitingCount;
    }

    public String getUserMessage() {
        return getMessage();
    }
}
