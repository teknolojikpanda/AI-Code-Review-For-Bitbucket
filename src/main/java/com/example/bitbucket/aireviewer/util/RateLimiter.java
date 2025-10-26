package com.example.bitbucket.aireviewer.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Token bucket rate limiter implementation.
 *
 * Limits the rate of operations using a sliding window algorithm.
 * Ensures that no more than maxRequests operations occur within
 * the specified time window.
 *
 * Thread-safe implementation suitable for concurrent use.
 */
public class RateLimiter {

    private static final Logger log = LoggerFactory.getLogger(RateLimiter.class);

    private final String name;
    private final int maxRequests;
    private final Duration timeWindow;
    private final BlockingQueue<Instant> requestTimestamps;
    private final long minDelayMs;
    private volatile Instant lastRequestTime;

    /**
     * Creates a new rate limiter.
     *
     * @param name unique name for this rate limiter (for logging)
     * @param maxRequests maximum number of requests allowed in the time window
     * @param timeWindow duration of the sliding time window
     */
    public RateLimiter(@Nonnull String name, int maxRequests, @Nonnull Duration timeWindow) {
        this.name = name;
        this.maxRequests = maxRequests;
        this.timeWindow = timeWindow;
        this.requestTimestamps = new LinkedBlockingQueue<>();
        this.minDelayMs = timeWindow.toMillis() / maxRequests;
    }

    /**
     * Acquires permission to proceed with an operation.
     * This method blocks until permission is granted.
     *
     * If the rate limit has been reached, this method will sleep
     * until the oldest request falls outside the time window.
     *
     * @throws InterruptedException if the thread is interrupted while waiting
     */
    public void acquire() throws InterruptedException {
        try {
            Instant now = Instant.now();

            synchronized (this) {
                // Remove timestamps outside the time window
                cleanupOldTimestamps(now);

                // If we're at the limit, wait until we can proceed
                while (requestTimestamps.size() >= maxRequests) {
                    Instant oldest = requestTimestamps.peek();
                    if (oldest == null) {
                        break;
                    }

                    // Calculate how long until the oldest request expires
                    Instant windowStart = now.minus(timeWindow);
                    if (oldest.isBefore(windowStart)) {
                        // Oldest request is already outside the window, remove it
                        requestTimestamps.poll();
                        cleanupOldTimestamps(now);
                    } else {
                        // Need to wait for the oldest request to expire
                        long waitTimeMs = Duration.between(windowStart, oldest).toMillis();
                        if (waitTimeMs > 0) {
                            log.debug("Rate limiter [{}] waiting {}ms (queue size: {}/{})",
                                    name, waitTimeMs, requestTimestamps.size(), maxRequests);
                            Thread.sleep(waitTimeMs);
                            now = Instant.now();
                            cleanupOldTimestamps(now);
                        }
                    }
                }

                // Add minimum delay between requests if configured
                if (minDelayMs > 0 && lastRequestTime != null) {
                    long timeSinceLastMs = Duration.between(lastRequestTime, now).toMillis();
                    if (timeSinceLastMs < minDelayMs) {
                        long delayMs = minDelayMs - timeSinceLastMs;
                        log.debug("Rate limiter [{}] enforcing minimum delay of {}ms", name, delayMs);
                        Thread.sleep(delayMs);
                        now = Instant.now();
                    }
                }

                // Record this request
                requestTimestamps.offer(now);
                lastRequestTime = now;
                log.debug("Rate limiter [{}] acquired (queue size: {}/{})",
                        name, requestTimestamps.size(), maxRequests);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw e;
        }
    }

    /**
     * Attempts to acquire permission without blocking.
     *
     * @return true if permission was granted, false if rate limit reached
     */
    public synchronized boolean tryAcquire() {
        Instant now = Instant.now();
        cleanupOldTimestamps(now);

        if (requestTimestamps.size() < maxRequests) {
            requestTimestamps.offer(now);
            lastRequestTime = now;
            log.debug("Rate limiter [{}] acquired non-blocking (queue size: {}/{})",
                    name, requestTimestamps.size(), maxRequests);
            return true;
        }

        log.debug("Rate limiter [{}] rejected (queue full: {}/{})",
                name, requestTimestamps.size(), maxRequests);
        return false;
    }

    /**
     * Attempts to acquire permission with a timeout.
     *
     * @param timeout maximum time to wait
     * @param unit time unit for the timeout
     * @return true if permission was granted, false if timeout elapsed
     * @throws InterruptedException if the thread is interrupted while waiting
     */
    public boolean tryAcquire(long timeout, @Nonnull TimeUnit unit) throws InterruptedException {
        long timeoutMs = unit.toMillis(timeout);
        long startTime = System.currentTimeMillis();

        synchronized (this) {
            Instant now = Instant.now();
            cleanupOldTimestamps(now);

            while (requestTimestamps.size() >= maxRequests) {
                long elapsedMs = System.currentTimeMillis() - startTime;
                if (elapsedMs >= timeoutMs) {
                    log.debug("Rate limiter [{}] timeout after {}ms", name, elapsedMs);
                    return false;
                }

                Instant oldest = requestTimestamps.peek();
                if (oldest == null) {
                    break;
                }

                Instant windowStart = now.minus(timeWindow);
                if (oldest.isBefore(windowStart)) {
                    requestTimestamps.poll();
                    cleanupOldTimestamps(now);
                } else {
                    long waitTimeMs = Math.min(
                            Duration.between(windowStart, oldest).toMillis(),
                            timeoutMs - elapsedMs
                    );
                    if (waitTimeMs > 0) {
                        Thread.sleep(waitTimeMs);
                        now = Instant.now();
                        cleanupOldTimestamps(now);
                    }
                }
            }

            if (requestTimestamps.size() < maxRequests) {
                requestTimestamps.offer(now);
                lastRequestTime = now;
                log.debug("Rate limiter [{}] acquired after {}ms (queue size: {}/{})",
                        name, System.currentTimeMillis() - startTime,
                        requestTimestamps.size(), maxRequests);
                return true;
            }

            return false;
        }
    }

    /**
     * Removes timestamps that fall outside the current time window.
     *
     * @param now current time
     */
    private void cleanupOldTimestamps(@Nonnull Instant now) {
        Instant windowStart = now.minus(timeWindow);
        while (!requestTimestamps.isEmpty()) {
            Instant oldest = requestTimestamps.peek();
            if (oldest != null && oldest.isBefore(windowStart)) {
                requestTimestamps.poll();
            } else {
                break;
            }
        }
    }

    /**
     * Resets the rate limiter, clearing all recorded timestamps.
     */
    public synchronized void reset() {
        log.info("Rate limiter [{}] reset", name);
        requestTimestamps.clear();
        lastRequestTime = null;
    }

    /**
     * Gets the current number of requests in the time window.
     *
     * @return number of active requests
     */
    public synchronized int getCurrentLoad() {
        cleanupOldTimestamps(Instant.now());
        return requestTimestamps.size();
    }

    /**
     * Gets the maximum number of requests allowed.
     *
     * @return max requests
     */
    public int getMaxRequests() {
        return maxRequests;
    }

    /**
     * Gets the time window duration.
     *
     * @return time window
     */
    @Nonnull
    public Duration getTimeWindow() {
        return timeWindow;
    }
}
