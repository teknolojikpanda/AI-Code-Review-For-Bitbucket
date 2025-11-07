package com.teknolojikpanda.bitbucket.aireviewer.service;

import com.atlassian.plugin.spring.scanner.annotation.export.ExportAsService;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
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

    private final AIReviewerConfigService configService;
    private final ThreadPoolExecutor executor;
    private volatile int poolSize;
    private volatile long lastRefresh;

    @Inject
    public ReviewWorkerPool(AIReviewerConfigService configService) {
        this.configService = Objects.requireNonNull(configService, "configService");
        this.poolSize = resolvePoolSize(configService.getConfigurationAsMap());
        this.executor = new ThreadPoolExecutor(
                poolSize,
                poolSize,
                60L,
                TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(),
                new WorkerThreadFactory());
        this.executor.allowCoreThreadTimeOut(false);
        this.lastRefresh = System.currentTimeMillis();
    }

    public <T> T execute(Callable<T> task) {
        Objects.requireNonNull(task, "task");
        refreshPoolSizeIfNeeded();
        try {
            return executor.submit(task).get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ReviewSchedulingInterruptedException("Interrupted while waiting for review worker", e);
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

    private void refreshPoolSizeIfNeeded() {
        long now = System.currentTimeMillis();
        if (now - lastRefresh < REFRESH_INTERVAL_MS) {
            return;
        }
        synchronized (this) {
            if (now - lastRefresh < REFRESH_INTERVAL_MS) {
                return;
            }
            int desired = resolvePoolSize(configService.getConfigurationAsMap());
            if (desired != poolSize) {
                executor.setCorePoolSize(desired);
                executor.setMaximumPoolSize(desired);
                poolSize = desired;
            }
            lastRefresh = now;
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
}
