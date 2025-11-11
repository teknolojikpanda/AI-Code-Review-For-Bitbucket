package com.teknolojikpanda.bitbucket.aireviewer.service;

import com.atlassian.plugin.spring.scanner.annotation.export.ExportAsService;
import com.teknolojikpanda.bitbucket.aireviewer.service.GuardrailsRateLimitStore.IncidentAggregate;
import com.teknolojikpanda.bitbucket.aireviewer.service.GuardrailsRateLimitStore.WindowSample;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;
import java.time.Instant;
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
    private final GuardrailsRateLimitStore rateLimitStore;
    private final GuardrailsRateLimitOverrideService overrideService;
    private final ConcurrentHashMap<String, Long> repoSnoozes = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> projectSnoozes = new ConcurrentHashMap<>();

    private volatile int repoLimitPerHour;
    private volatile int projectLimitPerHour;
    private volatile long lastRefresh;

    @Inject
    public ReviewRateLimiter(AIReviewerConfigService configService,
                             GuardrailsRateLimitStore rateLimitStore,
                             GuardrailsRateLimitOverrideService overrideService) {
        this.configService = Objects.requireNonNull(configService, "configService");
        this.rateLimitStore = Objects.requireNonNull(rateLimitStore, "rateLimitStore");
        this.overrideService = Objects.requireNonNull(overrideService, "overrideService");
        this.repoLimitPerHour = DEFAULT_REPO_LIMIT;
        this.projectLimitPerHour = DEFAULT_PROJECT_LIMIT;
        this.lastRefresh = 0L;
    }

    public void acquire(@Nullable String projectKey, @Nullable String repositorySlug) {
        refreshLimitsIfNeeded();
        long now = System.currentTimeMillis();
        long windowStart = now - (now % WINDOW_MS);

        if (repositorySlug != null) {
            int repoLimit = overrideService.resolveRepoLimit(projectKey, repositorySlug, repoLimitPerHour);
            if (repoLimit > 0) {
                String snoozeKey = repositorySlug.toLowerCase(Locale.ROOT);
                if (!isSnoozed(repoSnoozes, snoozeKey, now)) {
                    try {
                        rateLimitStore.acquireToken(
                                GuardrailsRateLimitScope.REPOSITORY,
                                repositorySlug,
                                repoLimit,
                                windowStart,
                                WINDOW_MS,
                                now);
                    } catch (RateLimitExceededException ex) {
                        recordThrottleIncident(
                                GuardrailsRateLimitScope.REPOSITORY,
                                repositorySlug,
                                projectKey,
                                repositorySlug,
                                repoLimit,
                                ex.getRetryAfterMillis(),
                                now);
                        throw ex;
                    }
                }
            }
        }
        if (projectKey != null) {
            int projectLimit = overrideService.resolveProjectLimit(projectKey, projectLimitPerHour);
            if (projectLimit > 0) {
                String snoozeKey = projectKey.toLowerCase(Locale.ROOT);
                if (!isSnoozed(projectSnoozes, snoozeKey, now)) {
                    try {
                        rateLimitStore.acquireToken(
                                GuardrailsRateLimitScope.PROJECT,
                                projectKey,
                                projectLimit,
                                windowStart,
                                WINDOW_MS,
                                now);
                    } catch (RateLimitExceededException ex) {
                        recordThrottleIncident(
                                GuardrailsRateLimitScope.PROJECT,
                                projectKey,
                                projectKey,
                                repositorySlug,
                                projectLimit,
                                ex.getRetryAfterMillis(),
                                now);
                        throw ex;
                    }
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
        List<WindowSample> repoSamples = sampleSize > 0
                ? rateLimitStore.getTopWindows(GuardrailsRateLimitScope.REPOSITORY, sampleSize)
                : Collections.emptyList();
        List<WindowSample> projectSamples = sampleSize > 0
                ? rateLimitStore.getTopWindows(GuardrailsRateLimitScope.PROJECT, sampleSize)
                : Collections.emptyList();
        Map<String, IncidentAggregate> repoAggregates = aggregateIncidents(GuardrailsRateLimitScope.REPOSITORY, sampleSize, now);
        Map<String, IncidentAggregate> projectAggregates = aggregateIncidents(GuardrailsRateLimitScope.PROJECT, sampleSize, now);
        Map<String, RateLimitSnapshot.BucketState> repoStates = windowSamplesToMap(repoSamples, repoAggregates, now);
        Map<String, RateLimitSnapshot.BucketState> projectStates = windowSamplesToMap(projectSamples, projectAggregates, now);
        return new RateLimitSnapshot(
                repoLimitPerHour,
                projectLimitPerHour,
                repoSamples.size(),
                projectSamples.size(),
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

    private Map<String, IncidentAggregate> aggregateIncidents(GuardrailsRateLimitScope scope,
                                                              int sampleLimit,
                                                              long now) {
        if (sampleLimit <= 0) {
            return Collections.emptyMap();
        }
        long since = Math.max(0L, now - WINDOW_MS);
        List<IncidentAggregate> aggregates = rateLimitStore.aggregateIncidents(scope, since, sampleLimit);
        if (aggregates.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, IncidentAggregate> map = new LinkedHashMap<>();
        for (IncidentAggregate aggregate : aggregates) {
            map.put(aggregate.getIdentifier(), aggregate);
        }
        return map;
    }

    private Map<String, RateLimitSnapshot.BucketState> windowSamplesToMap(List<WindowSample> samples,
                                                                          Map<String, IncidentAggregate> aggregates,
                                                                          long now) {
        if (samples == null || samples.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, RateLimitSnapshot.BucketState> result = new LinkedHashMap<>(samples.size());
        for (WindowSample sample : samples) {
            IncidentAggregate aggregate = aggregates.get(sample.getIdentifier());
            int throttled = aggregate != null ? aggregate.getThrottledCount() : 0;
            long lastThrottleAt = aggregate != null ? aggregate.getLastOccurredAt() : 0L;
            long avgRetry = aggregate != null ? aggregate.getAverageRetryAfterMs() : 0L;
            result.put(sample.getIdentifier(), new RateLimitSnapshot.BucketState(
                    sample.getConsumed(),
                    sample.getLimitPerHour(),
                    sample.getRemaining(),
                    sample.estimateResetIn(WINDOW_MS, now),
                    sample.getUpdatedAt(),
                    throttled,
                    lastThrottleAt,
                    avgRetry));
        }
        return result;
    }

    private void recordThrottleIncident(GuardrailsRateLimitScope scope,
                                        @Nullable String identifier,
                                        @Nullable String projectKey,
                                        @Nullable String repositorySlug,
                                        int limitPerHour,
                                        long retryAfterMs,
                                        long occurredAt) {
        if (identifier == null) {
            return;
        }
        try {
            rateLimitStore.recordIncident(
                    scope,
                    identifier,
                    projectKey,
                    repositorySlug,
                    limitPerHour,
                    retryAfterMs,
                    occurredAt,
                    "rate-limit");
        } catch (Exception ex) {
            log.debug("Failed to record rate-limit incident for {}:{} - {}",
                    scope,
                    identifier,
                    ex.getMessage());
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
            private final long updatedAt;
            private final int throttledCount;
            private final long lastThrottleAt;
            private final long averageRetryAfterMs;

            BucketState(int consumed,
                        int limit,
                        int remaining,
                        long resetInMs,
                        long updatedAt,
                        int throttledCount,
                        long lastThrottleAt,
                        long averageRetryAfterMs) {
                this.consumed = consumed;
                this.limit = limit;
                this.remaining = remaining;
                this.resetInMs = resetInMs;
                this.updatedAt = updatedAt;
                this.throttledCount = throttledCount;
                this.lastThrottleAt = lastThrottleAt;
                this.averageRetryAfterMs = averageRetryAfterMs;
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

            public long getUpdatedAt() {
                return updatedAt;
            }

            public int getThrottledCount() {
                return throttledCount;
            }

            public long getLastThrottleAt() {
                return lastThrottleAt;
            }

            public long getAverageRetryAfterMs() {
                return averageRetryAfterMs;
            }
        }
    }
}
