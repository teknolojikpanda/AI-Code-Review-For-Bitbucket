package com.teknolojikpanda.bitbucket.aireviewer.service;

import com.atlassian.plugin.spring.scanner.annotation.export.ExportAsService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Coordinates how many AI review runs may execute concurrently and how many requests
 * may wait for a slot before being rejected.
 */
@Named
@Singleton
@ExportAsService(ReviewConcurrencyController.class)
public class ReviewConcurrencyController {

    private static final int DEFAULT_MAX_CONCURRENT = 2;
    private static final int DEFAULT_MAX_QUEUE = 25;
    private static final long REFRESH_INTERVAL_MS = TimeUnit.SECONDS.toMillis(15);

    private final AIReviewerConfigService configService;
    private final ReviewSchedulerStateService schedulerStateService;
    private final AdjustableSemaphore semaphore;
    private final AtomicInteger waitingCount = new AtomicInteger();

    private volatile int maxConcurrent;
    private volatile int maxQueueSize;
    private volatile long lastRefreshTimestamp;

    private static final Logger log = LoggerFactory.getLogger(ReviewConcurrencyController.class);

    @Inject
    public ReviewConcurrencyController(AIReviewerConfigService configService,
                                       ReviewSchedulerStateService schedulerStateService) {
        this.configService = Objects.requireNonNull(configService, "configService");
        this.schedulerStateService = Objects.requireNonNull(schedulerStateService, "schedulerStateService");
        this.maxConcurrent = DEFAULT_MAX_CONCURRENT;
        this.maxQueueSize = DEFAULT_MAX_QUEUE;
        this.semaphore = new AdjustableSemaphore(this.maxConcurrent, true);
        this.lastRefreshTimestamp = 0L;
    }

    /**
     * Attempts to reserve a concurrency slot. If all slots are busy and the queued waiters
     * already exceed {@code maxQueuedReviews}, a {@link ReviewQueueFullException} is thrown.
     */
    @Nonnull
    public Slot acquire(@Nonnull ReviewExecutionRequest request) {
        Objects.requireNonNull(request, "request");
        ReviewSchedulerStateService.SchedulerState schedulerState = schedulerStateService.getState();
        if (!schedulerState.isAcceptingNewRuns()) {
            throw new ReviewSchedulerPausedException(schedulerState);
        }
        refreshLimitsIfNeeded();
        if (semaphore.tryAcquire()) {
            return new Slot();
        }
        if (!allowQueuedWaiter()) {
            throw new ReviewQueueFullException(buildQueueMessage(request), maxConcurrent, maxQueueSize, waitingCount.get());
        }
        try {
            semaphore.acquire();
            return new Slot();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ReviewSchedulingInterruptedException("Interrupted while waiting for AI review capacity", e);
        } finally {
            waitingCount.decrementAndGet();
        }
    }

    public int getMaxConcurrent() {
        return maxConcurrent;
    }

    public int getMaxQueueSize() {
        return maxQueueSize;
    }

    public int getWaitingCount() {
        return waitingCount.get();
    }

    public int getActiveReviews() {
        return Math.max(0, Math.min(maxConcurrent, maxConcurrent - semaphore.availablePermits()));
    }

    public QueueStats snapshot() {
        refreshLimitsIfNeeded();
        ReviewSchedulerStateService.SchedulerState state = schedulerStateService.getState();
        return new QueueStats(
                maxConcurrent,
                maxQueueSize,
                getActiveReviews(),
                Math.max(0, waitingCount.get()),
                System.currentTimeMillis(),
                state);
    }

    private boolean allowQueuedWaiter() {
        int queued = waitingCount.incrementAndGet();
        if (queued > maxQueueSize) {
            waitingCount.decrementAndGet();
            return false;
        }
        return true;
    }

    private void refreshLimitsIfNeeded() {
        long now = System.currentTimeMillis();
        if (now - lastRefreshTimestamp < REFRESH_INTERVAL_MS) {
            return;
        }
        synchronized (this) {
            if (now - lastRefreshTimestamp < REFRESH_INTERVAL_MS) {
                return;
            }
            Map<String, Object> map = fetchConfigSafely();
            int desiredConcurrent = resolveInt(map.get("maxConcurrentReviews"), DEFAULT_MAX_CONCURRENT, 1, 32);
            int desiredQueue = resolveInt(map.get("maxQueuedReviews"), DEFAULT_MAX_QUEUE, 0, 1000);
            if (desiredConcurrent != this.maxConcurrent) {
                adjustSemaphore(desiredConcurrent - this.maxConcurrent);
                this.maxConcurrent = desiredConcurrent;
            }
            this.maxQueueSize = desiredQueue;
            this.lastRefreshTimestamp = now;
        }
    }

    private Map<String, Object> fetchConfigSafely() {
        try {
            return configService.getConfigurationAsMap();
        } catch (Exception ex) {
            log.debug("Unable to fetch configuration while initializing concurrency controller; using defaults: {}", ex.getMessage());
            return Map.of();
        }
    }

    private void adjustSemaphore(int delta) {
        if (delta > 0) {
            semaphore.release(delta);
        } else if (delta < 0) {
            semaphore.shrink(-delta);
        }
    }

    private int resolveInt(Object raw, int defaultValue, int min, int max) {
        int value = defaultValue;
        if (raw instanceof Number) {
            value = ((Number) raw).intValue();
        } else if (raw instanceof String) {
            try {
                value = Integer.parseInt(((String) raw).trim());
            } catch (NumberFormatException ignored) {
                value = defaultValue;
            }
        }
        if (value < min) {
            value = min;
        } else if (value > max) {
            value = max;
        }
        return value;
    }

    private String buildQueueMessage(ReviewExecutionRequest request) {
        String scope = "";
        if (request.getProjectKey() != null && request.getRepositorySlug() != null) {
            scope = String.format(" for %s/%s", request.getProjectKey(), request.getRepositorySlug());
        }
        return String.format("AI review capacity exhausted%s. Please retry shortly.", scope);
    }

    public static final class ReviewExecutionRequest {
        private final String projectKey;
        private final String repositorySlug;
        private final long pullRequestId;
        private final boolean manual;
        private final boolean update;
        private final boolean force;

        public ReviewExecutionRequest(String projectKey,
                                      String repositorySlug,
                                      long pullRequestId,
                                      boolean manual,
                                      boolean update,
                                      boolean force) {
            this.projectKey = projectKey;
            this.repositorySlug = repositorySlug;
            this.pullRequestId = pullRequestId;
            this.manual = manual;
            this.update = update;
            this.force = force;
        }

        public String getProjectKey() {
            return projectKey;
        }

        public String getRepositorySlug() {
            return repositorySlug;
        }

        public long getPullRequestId() {
            return pullRequestId;
        }

        public boolean isManual() {
            return manual;
        }

        public boolean isUpdate() {
            return update;
        }

        public boolean isForce() {
            return force;
        }
    }

    public final class Slot implements AutoCloseable {
        private boolean released;

        private Slot() {
        }

        @Override
        public void close() {
            if (released) {
                return;
            }
            released = true;
            semaphore.release();
        }
    }

    private static final class AdjustableSemaphore extends Semaphore {
        AdjustableSemaphore(int permits, boolean fair) {
            super(permits, fair);
        }

        void shrink(int reduction) {
            super.reducePermits(reduction);
        }
    }

    public static final class QueueStats {
        private final int maxConcurrent;
        private final int maxQueued;
        private final int active;
        private final int waiting;
        private final long capturedAt;
        private final ReviewSchedulerStateService.SchedulerState schedulerState;

        public QueueStats(int maxConcurrent,
                          int maxQueued,
                          int active,
                          int waiting,
                          long capturedAt,
                          ReviewSchedulerStateService.SchedulerState schedulerState) {
            this.maxConcurrent = maxConcurrent;
            this.maxQueued = maxQueued;
            this.active = active;
            this.waiting = waiting;
            this.capturedAt = capturedAt;
            this.schedulerState = schedulerState;
        }

        public int getMaxConcurrent() {
            return maxConcurrent;
        }

        public int getMaxQueued() {
            return maxQueued;
        }

        public int getActive() {
            return active;
        }

        public int getWaiting() {
            return waiting;
        }

        public long getCapturedAt() {
            return capturedAt;
        }

        public ReviewSchedulerStateService.SchedulerState getSchedulerState() {
            return schedulerState;
        }
    }
}
