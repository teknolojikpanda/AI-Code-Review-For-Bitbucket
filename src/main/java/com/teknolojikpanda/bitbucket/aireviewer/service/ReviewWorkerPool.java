package com.teknolojikpanda.bitbucket.aireviewer.service;

import com.atlassian.plugin.spring.scanner.annotation.export.ExportAsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Dedicated worker pool for running heavy review operations off the request thread.
 */
@Named
@Singleton
@ExportAsService(ReviewWorkerPool.class)
public class ReviewWorkerPool {

    private static final int DEFAULT_POOL_SIZE = 4;
    private static final long REFRESH_INTERVAL_MS = TimeUnit.SECONDS.toMillis(30);
    private static final Logger log = LoggerFactory.getLogger(ReviewWorkerPool.class);

    private final AIReviewerConfigService configService;
    private final ThreadPoolExecutor executor;
    private volatile int poolSize;
    private volatile long lastRefresh;

    @Inject
    public ReviewWorkerPool(AIReviewerConfigService configService) {
        this.configService = Objects.requireNonNull(configService, "configService");
        this.poolSize = DEFAULT_POOL_SIZE;
        this.executor = new ThreadPoolExecutor(
                poolSize,
                poolSize,
                60L,
                TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(),
                new WorkerThreadFactory());
        this.executor.allowCoreThreadTimeOut(false);
        this.lastRefresh = 0L;
    }

    public <T> T execute(Callable<T> task) {
        return join(submit(task));
    }

    public <T> Future<T> submit(Callable<T> task) {
        Objects.requireNonNull(task, "task");
        refreshPoolSizeIfNeeded();
        return executor.submit(task);
    }

    public <T> T join(Future<T> future) {
        Objects.requireNonNull(future, "future");
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ReviewSchedulingInterruptedException("Interrupted while waiting for review worker", e);
        } catch (CancellationException e) {
            throw e;
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException) {
                throw (RuntimeException) cause;
            }
            if (cause instanceof Error) {
                throw (Error) cause;
            }
            throw new RuntimeException(cause);
        }
    }

    public void shutdown() {
        executor.shutdownNow();
    }

    public WorkerPoolSnapshot snapshot() {
        refreshPoolSizeIfNeeded();
        ThreadPoolExecutor exec = this.executor;
        return new WorkerPoolSnapshot(
                poolSize,
                exec.getActiveCount(),
                exec.getQueue().size(),
                exec.getPoolSize(),
                exec.getLargestPoolSize(),
                exec.getTaskCount(),
                exec.getCompletedTaskCount(),
                System.currentTimeMillis());
    }

    private void refreshPoolSizeIfNeeded() {
        long now = System.currentTimeMillis();
        if (now - lastRefresh < REFRESH_INTERVAL_MS) {
            return;
        }
        synchronized (this) {
            if (now - lastRefresh < REFRESH_INTERVAL_MS) {
                return;
            }
            int desired = resolvePoolSize(fetchConfigSafely());
            if (desired != poolSize) {
                executor.setCorePoolSize(desired);
                executor.setMaximumPoolSize(desired);
                poolSize = desired;
            }
            lastRefresh = now;
        }
    }

    private Map<String, Object> fetchConfigSafely() {
        try {
            return configService.getConfigurationAsMap();
        } catch (Exception ex) {
            log.debug("Unable to fetch configuration while refreshing worker pool; using defaults: {}", ex.getMessage());
            return java.util.Collections.emptyMap();
        }
    }

    private int resolvePoolSize(Map<String, Object> config) {
        Object raw = config.get("maxConcurrentReviews");
        int value = DEFAULT_POOL_SIZE;
        if (raw instanceof Number) {
            value = ((Number) raw).intValue();
        } else if (raw instanceof String) {
            try {
                value = Integer.parseInt(((String) raw).trim());
            } catch (NumberFormatException ignored) {
                value = DEFAULT_POOL_SIZE;
            }
        }
        return Math.max(1, Math.min(32, value));
    }

    private static final class WorkerThreadFactory implements ThreadFactory {
        private static final AtomicInteger POOL_COUNTER = new AtomicInteger();
        private final AtomicInteger threadCounter = new AtomicInteger();
        private final String prefix;

        WorkerThreadFactory() {
            this.prefix = "ai-review-worker-" + POOL_COUNTER.incrementAndGet() + "-";
        }

        @Override
        public Thread newThread(Runnable r) {
            Thread thread = new Thread(r, prefix + threadCounter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }
    }

    public static final class WorkerPoolSnapshot {
        private final int configuredSize;
        private final int activeThreads;
        private final int queuedTasks;
        private final int currentPoolSize;
        private final int largestPoolSize;
        private final long totalTasks;
        private final long completedTasks;
        private final long capturedAt;

        WorkerPoolSnapshot(int configuredSize,
                           int activeThreads,
                           int queuedTasks,
                           int currentPoolSize,
                           int largestPoolSize,
                           long totalTasks,
                           long completedTasks,
                           long capturedAt) {
            this.configuredSize = configuredSize;
            this.activeThreads = activeThreads;
            this.queuedTasks = queuedTasks;
            this.currentPoolSize = currentPoolSize;
            this.largestPoolSize = largestPoolSize;
            this.totalTasks = totalTasks;
            this.completedTasks = completedTasks;
            this.capturedAt = capturedAt;
        }

        public int getConfiguredSize() {
            return configuredSize;
        }

        public int getActiveThreads() {
            return activeThreads;
        }

        public int getQueuedTasks() {
            return queuedTasks;
        }

        public int getCurrentPoolSize() {
            return currentPoolSize;
        }

        public int getLargestPoolSize() {
            return largestPoolSize;
        }

        public long getTotalTasks() {
            return totalTasks;
        }

        public long getCompletedTasks() {
            return completedTasks;
        }

        public long getCapturedAt() {
            return capturedAt;
        }
    }
}
