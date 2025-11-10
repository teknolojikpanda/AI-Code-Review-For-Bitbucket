package com.teknolojikpanda.bitbucket.aireviewer.service;

import com.atlassian.plugin.spring.scanner.annotation.export.ExportAsService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;
import java.time.Instant;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
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

    private static final Logger log = LoggerFactory.getLogger(ReviewRateLimiter.class);
    private static final long WINDOW_MS = TimeUnit.HOURS.toMillis(1);
    private static final int DEFAULT_REPO_LIMIT = 12;
    private static final int DEFAULT_PROJECT_LIMIT = 60;
    private static final long REFRESH_INTERVAL_MS = TimeUnit.SECONDS.toMillis(30);
    private static final int DEFAULT_SNAPSHOT_BUCKETS = 10;
    private static final long MIN_SNOOZE_MS = TimeUnit.SECONDS.toMillis(5);
    private static final long MAX_SNOOZE_MS = TimeUnit.MINUTES.toMillis(30);

    private final AIReviewerConfigService configService;
    private final ConcurrentHashMap<String, Bucket> repoBuckets = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Bucket> projectBuckets = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> repoSnoozes = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> projectSnoozes = new ConcurrentHashMap<>();

    private volatile int repoLimitPerHour;
    private volatile int projectLimitPerHour;
    private volatile long lastRefresh;

    @Inject
    public ReviewRateLimiter(AIReviewerConfigService configService) {
        this.configService = Objects.requireNonNull(configService, "configService");
        this.repoLimitPerHour = DEFAULT_REPO_LIMIT;
        this.projectLimitPerHour = DEFAULT_PROJECT_LIMIT;
        this.lastRefresh = 0L;
    }

    public void acquire(@Nullable String projectKey, @Nullable String repositorySlug) {
        refreshLimitsIfNeeded();
        long now = System.currentTimeMillis();
        if (repositorySlug != null && repoLimitPerHour > 0) {
            String repoKey = repositorySlug.toLowerCase(Locale.ROOT);
            if (!isSnoozed(repoSnoozes, repoKey, now)) {
                Bucket bucket = repoBuckets.computeIfAbsent(repoKey, k -> new Bucket());
                if (!bucket.tryConsume(repoLimitPerHour, now)) {
                    throw RateLimitExceededException.repository(repositorySlug, repoLimitPerHour, bucket.retryAfter(now));
                }
            }
        }
        if (projectKey != null && projectLimitPerHour > 0) {
            String project = projectKey.toLowerCase(Locale.ROOT);
            if (!isSnoozed(projectSnoozes, project, now)) {
                Bucket bucket = projectBuckets.computeIfAbsent(project, k -> new Bucket());
                if (!bucket.tryConsume(projectLimitPerHour, now)) {
                    throw RateLimitExceededException.project(projectKey, projectLimitPerHour, bucket.retryAfter(now));
                }
            }
        }
    }

    public void autoSnooze(@Nullable String projectKey,
                           @Nullable String repositorySlug,
                           long durationMs,
                           @Nullable String reason) {
        long clamped = clampDuration(durationMs);
        if (clamped <= 0) {
            return;
        }
        long expiresAt = System.currentTimeMillis() + clamped;
        if (repositorySlug != null && !repositorySlug.trim().isEmpty()) {
            repoSnoozes.put(repositorySlug.toLowerCase(Locale.ROOT), expiresAt);
        }
        if (projectKey != null && !projectKey.trim().isEmpty()) {
            projectSnoozes.put(projectKey.toLowerCase(Locale.ROOT), expiresAt);
        }
        if (log.isDebugEnabled()) {
            log.debug("Auto-snoozed rate limit for {}/{} until {} ({})",
                    projectKey,
                    repositorySlug,
                    Instant.ofEpochMilli(expiresAt),
                    reason != null ? reason : "auto");
        }
    }

    public RateLimitSnapshot snapshot() {
        return snapshot(DEFAULT_SNAPSHOT_BUCKETS);
    }

    public RateLimitSnapshot snapshot(int maxBucketSamples) {
        refreshLimitsIfNeeded();
        int sampleSize = Math.max(0, maxBucketSamples);
        long now = System.currentTimeMillis();
        Map<String, RateLimitSnapshot.BucketState> repoStates =
                captureTopBuckets(repoBuckets, repoLimitPerHour, sampleSize, now);
        Map<String, RateLimitSnapshot.BucketState> projectStates =
                captureTopBuckets(projectBuckets, projectLimitPerHour, sampleSize, now);
        return new RateLimitSnapshot(
                repoLimitPerHour,
                projectLimitPerHour,
                repoBuckets.size(),
                projectBuckets.size(),
                repoStates,
                projectStates,
                now);
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
        Map<String, Object> config = fetchConfigSafely();
        this.repoLimitPerHour = resolveLimit(config.get("repoRateLimitPerHour"), DEFAULT_REPO_LIMIT);
        this.projectLimitPerHour = resolveLimit(config.get("projectRateLimitPerHour"), DEFAULT_PROJECT_LIMIT);
    }

    private boolean isSnoozed(ConcurrentHashMap<String, Long> snoozes, String key, long now) {
        if (key == null) {
            return false;
        }
        Long expires = snoozes.get(key);
        if (expires == null) {
            return false;
        }
        if (expires <= now) {
            snoozes.remove(key, expires);
            return false;
        }
        return true;
    }

    private long clampDuration(long durationMs) {
        if (durationMs <= 0) {
            return 0L;
        }
        long clamped = Math.min(durationMs, MAX_SNOOZE_MS);
        return Math.max(clamped, MIN_SNOOZE_MS);
    }

    private Map<String, Object> fetchConfigSafely() {
        try {
            return configService.getConfigurationAsMap();
        } catch (Exception ex) {
            log.debug("Unable to fetch configuration while refreshing rate limits; using defaults: {}", ex.getMessage());
            return Collections.emptyMap();
        }
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

        synchronized RateLimitSnapshot.BucketState snapshot(int limit, long now) {
            int remaining = Math.max(0, limit - count);
            long resetIn = Math.max(0, (windowStart + WINDOW_MS) - now);
            return new RateLimitSnapshot.BucketState(count, limit, remaining, resetIn);
        }
    }

    public static final class RateLimitSnapshot {
        private final int repoLimit;
        private final int projectLimit;
        private final int trackedRepoBuckets;
        private final int trackedProjectBuckets;
        private final Map<String, BucketState> topRepoBuckets;
        private final Map<String, BucketState> topProjectBuckets;
        private final long capturedAt;

        RateLimitSnapshot(int repoLimit,
                          int projectLimit,
                          int trackedRepoBuckets,
                          int trackedProjectBuckets,
                          Map<String, BucketState> topRepoBuckets,
                          Map<String, BucketState> topProjectBuckets,
                          long capturedAt) {
            this.repoLimit = repoLimit;
            this.projectLimit = projectLimit;
            this.trackedRepoBuckets = trackedRepoBuckets;
            this.trackedProjectBuckets = trackedProjectBuckets;
            this.topRepoBuckets = topRepoBuckets;
            this.topProjectBuckets = topProjectBuckets;
            this.capturedAt = capturedAt;
        }

        public int getRepoLimit() {
            return repoLimit;
        }

        public int getProjectLimit() {
            return projectLimit;
        }

        public int getTrackedRepoBuckets() {
            return trackedRepoBuckets;
        }

        public int getTrackedProjectBuckets() {
            return trackedProjectBuckets;
        }

        public Map<String, BucketState> getTopRepoBuckets() {
            return topRepoBuckets;
        }

        public Map<String, BucketState> getTopProjectBuckets() {
            return topProjectBuckets;
        }

        public long getCapturedAt() {
            return capturedAt;
        }

        public static final class BucketState {
            private final int consumed;
            private final int limit;
            private final int remaining;
            private final long resetInMs;

            BucketState(int consumed, int limit, int remaining, long resetInMs) {
                this.consumed = consumed;
                this.limit = limit;
                this.remaining = remaining;
                this.resetInMs = resetInMs;
            }

            public int getConsumed() {
                return consumed;
            }

            public int getLimit() {
                return limit;
            }

            public int getRemaining() {
                return remaining;
            }

            public long getResetInMs() {
                return resetInMs;
            }
        }
    }

    private Map<String, RateLimitSnapshot.BucketState> captureTopBuckets(ConcurrentHashMap<String, Bucket> buckets,
                                                                         int limit,
                                                                         int maxSamples,
                                                                         long now) {
        if (buckets.isEmpty() || maxSamples <= 0 || limit <= 0) {
            return Collections.emptyMap();
        }
        List<Map.Entry<String, RateLimitSnapshot.BucketState>> snapshots = new ArrayList<>();
        buckets.forEach((key, bucket) -> {
            RateLimitSnapshot.BucketState state = bucket.snapshot(limit, now);
            if (state.getConsumed() > 0) {
                snapshots.add(new AbstractMap.SimpleEntry<>(key, state));
            }
        });
        if (snapshots.isEmpty()) {
            return Collections.emptyMap();
        }
        snapshots.sort((a, b) -> Integer.compare(b.getValue().getConsumed(), a.getValue().getConsumed()));
        Map<String, RateLimitSnapshot.BucketState> result = new LinkedHashMap<>();
        int count = 0;
        for (Map.Entry<String, RateLimitSnapshot.BucketState> entry : snapshots) {
            if (count++ >= maxSamples) {
                break;
            }
            result.put(entry.getKey(), entry.getValue());
        }
        return result;
    }
}
