package com.teknolojikpanda.bitbucket.aireviewer.service;

/**
 * Raised when a thread waiting for a review execution slot is interrupted.
 */
public class ReviewSchedulingInterruptedException extends RuntimeException {
    public ReviewSchedulingInterruptedException(String message, Throwable cause) {
        super(message, cause);
    }
}
