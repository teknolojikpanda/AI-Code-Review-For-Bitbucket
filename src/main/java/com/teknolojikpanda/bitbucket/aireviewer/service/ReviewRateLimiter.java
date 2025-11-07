package com.teknolojikpanda.bitbucket.aireviewer.service;

import com.atlassian.plugin.spring.scanner.annotation.export.ExportAsService;

import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Token-bucket style limiter that caps how many AI reviews a repository or project may run per hour.
 */
@Named
@Singleton
@ExportAsService(ReviewRateLimiter.class)
public class ReviewRateLimiter {

    private static final long WINDOW_MS = TimeUnit.HOURS.toMillis(1);
    private static final int DEFAULT_REPO_LIMIT = 12;
    private static final int DEFAULT_PROJECT_LIMIT = 60;
    private static final long REFRESH_INTERVAL_MS = TimeUnit.SECONDS.toMillis(30);

    private final AIReviewerConfigService configService;
    private final ConcurrentHashMap<String, Bucket> repoBuckets = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Bucket> projectBuckets = new ConcurrentHashMap<>();

    private volatile int repoLimitPerHour;
    private volatile int projectLimitPerHour;
    private volatile long lastRefresh;

    @Inject
    public ReviewRateLimiter(AIReviewerConfigService configService) {
        this.configService = Objects.requireNonNull(configService, "configService");
        refreshLimits();
    }

    public void acquire(@Nullable String projectKey, @Nullable String repositorySlug) {
        refreshLimitsIfNeeded();
        long now = System.currentTimeMillis();
        if (repositorySlug != null && repoLimitPerHour > 0) {
            Bucket bucket = repoBuckets.computeIfAbsent(repositorySlug.toLowerCase(), k -> new Bucket());
            if (!bucket.tryConsume(repoLimitPerHour, now)) {
                throw RateLimitExceededException.repository(repositorySlug, repoLimitPerHour, bucket.retryAfter(now));
            }
        }
        if (projectKey != null && projectLimitPerHour > 0) {
            Bucket bucket = projectBuckets.computeIfAbsent(projectKey.toLowerCase(), k -> new Bucket());
            if (!bucket.tryConsume(projectLimitPerHour, now)) {
                throw RateLimitExceededException.project(projectKey, projectLimitPerHour, bucket.retryAfter(now));
            }
        }
    }

    public RateLimitSnapshot snapshot() {
        refreshLimitsIfNeeded();
        return new RateLimitSnapshot(repoLimitPerHour, projectLimitPerHour);
    }

    private void refreshLimitsIfNeeded() {
        long now = System.currentTimeMillis();
        if (now - lastRefresh < REFRESH_INTERVAL_MS) {
            return;
        }
        synchronized (this) {
            if (now - lastRefresh < REFRESH_INTERVAL_MS) {
                return;
            }
            refreshLimits();
            lastRefresh = now;
        }
    }

    private void refreshLimits() {
        Map<String, Object> config = configService.getConfigurationAsMap();
        this.repoLimitPerHour = resolveLimit(config.get("repoRateLimitPerHour"), DEFAULT_REPO_LIMIT);
        this.projectLimitPerHour = resolveLimit(config.get("projectRateLimitPerHour"), DEFAULT_PROJECT_LIMIT);
    }

    private int resolveLimit(Object raw, int defaultValue) {
        if (raw instanceof Number) {
            return Math.max(0, ((Number) raw).intValue());
        }
        if (raw instanceof String) {
            try {
                return Math.max(0, Integer.parseInt(((String) raw).trim()));
            } catch (NumberFormatException ignored) {
                return defaultValue;
            }
        }
        return defaultValue;
    }

    private static final class Bucket {
        private int count;
        private long windowStart;

        synchronized boolean tryConsume(int limit, long now) {
            if (limit <= 0) {
                return true;
            }
            if (now - windowStart >= WINDOW_MS) {
                windowStart = now;
                count = 0;
            }
            if (count >= limit) {
                return false;
            }
            count++;
            return true;
        }

        synchronized long retryAfter(long now) {
            long resetAt = windowStart + WINDOW_MS;
            if (resetAt <= now) {
                return 0L;
            }
            return resetAt - now;
        }
    }

    public static final class RateLimitSnapshot {
        private final int repoLimit;
        private final int projectLimit;

        RateLimitSnapshot(int repoLimit, int projectLimit) {
            this.repoLimit = repoLimit;
            this.projectLimit = projectLimit;
        }

        public int getRepoLimit() {
            return repoLimit;
        }

        public int getProjectLimit() {
            return projectLimit;
        }
    }
}
