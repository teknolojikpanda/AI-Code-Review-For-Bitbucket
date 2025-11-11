package com.teknolojikpanda.bitbucket.aireviewer.service;

import com.atlassian.activeobjects.external.ActiveObjects;
import com.atlassian.plugin.spring.scanner.annotation.imports.ComponentImport;
import com.teknolojikpanda.bitbucket.aireviewer.ao.GuardrailsRateBucket;
import com.teknolojikpanda.bitbucket.aireviewer.ao.GuardrailsRateIncident;
import net.java.ao.DBParam;
import net.java.ao.Query;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Named
@Singleton
public class GuardrailsRateLimitStore {

    private static final Logger log = LoggerFactory.getLogger(GuardrailsRateLimitStore.class);
    private static final int MAX_TOP_BUCKETS = 50;
    private static final int MAX_INCIDENT_SAMPLES = 200;
    private static final long SNAPSHOT_TTL_MS = TimeUnit.SECONDS.toMillis(15);
    private static final long INCIDENT_TTL_MS = TimeUnit.SECONDS.toMillis(15);

    private final ActiveObjects ao;
    private final ConcurrentHashMap<GuardrailsRateLimitScope, SnapshotCache> snapshotCache = new ConcurrentHashMap<>();
    private volatile CachedIncidents recentIncidentsCache;

    @Inject
    public GuardrailsRateLimitStore(@ComponentImport ActiveObjects ao) {
        this.ao = Objects.requireNonNull(ao, "activeObjects");
    }

    public void acquireToken(GuardrailsRateLimitScope scope,
                             String identifier,
                             int limitPerHour,
                             long windowStart,
                             long windowDurationMs,
                             long now) {
        if (!supportsScope(scope) || identifier == null || identifier.trim().isEmpty() || limitPerHour <= 0) {
            return;
        }
        final String normalized = normalizeIdentifier(identifier);
        ao.executeInTransaction(() -> {
            GuardrailsRateBucket bucket = loadOrCreateBucket(scope, normalized, windowStart, now);
            if (bucket.getLimitPerHour() != limitPerHour) {
                bucket.setLimitPerHour(limitPerHour);
            }
            int consumed = bucket.getConsumed();
            if (consumed >= limitPerHour) {
                long retryAfter = Math.max(0L, (bucket.getWindowStart() + windowDurationMs) - now);
                throw scope == GuardrailsRateLimitScope.REPOSITORY
                        ? RateLimitExceededException.repository(identifier, limitPerHour, retryAfter)
                        : RateLimitExceededException.project(identifier, limitPerHour, retryAfter);
            }
            bucket.setConsumed(consumed + 1);
            bucket.setUpdatedAt(now);
            bucket.save();
            snapshotCache.remove(scope);
            return null;
        });
    }

    public List<WindowSample> getTopWindows(GuardrailsRateLimitScope scope, int maxSamples) {
        if (!supportsScope(scope)) {
            return Collections.emptyList();
        }
        int safeLimit = Math.min(MAX_TOP_BUCKETS, Math.max(0, maxSamples));
        if (safeLimit == 0) {
            return Collections.emptyList();
        }
        SnapshotCache cached = snapshotCache.get(scope);
        long now = System.currentTimeMillis();
        if (cached != null && cached.expiresAt > now) {
            return cached.samples;
        }
        List<WindowSample> samples = ao.executeInTransaction(() -> {
            GuardrailsRateBucket[] rows = ao.find(GuardrailsRateBucket.class,
                    Query.select()
                            .where("SCOPE = ?", scope.name())
                            .order("CONSUMED DESC")
                            .limit(safeLimit));
            if (rows.length == 0) {
                return Collections.emptyList();
            }
            List<WindowSample> result = new ArrayList<>(rows.length);
            for (GuardrailsRateBucket row : rows) {
                result.add(windowSample(scope, row));
            }
            return result;
        });
        snapshotCache.put(scope, new SnapshotCache(samples, now + SNAPSHOT_TTL_MS));
        return samples;
    }

    public void recordIncident(GuardrailsRateLimitScope scope,
                               String identifier,
                               @Nullable String projectKey,
                               @Nullable String repoSlug,
                               int limitPerHour,
                               long retryAfterMs,
                               long occurredAt,
                               @Nullable String reason) {
        if (!supportsScope(scope)) {
            return;
        }
        final String normalized = normalizeIdentifier(identifier);
        ao.executeInTransaction(() -> {
            GuardrailsRateIncident incident = ao.create(GuardrailsRateIncident.class,
                    new DBParam("SCOPE", scope.name()),
                    new DBParam("IDENTIFIER", normalized),
                    new DBParam("PROJECT_KEY", sanitize(projectKey)),
                    new DBParam("REPOSITORY_SLUG", sanitize(repoSlug)),
                    new DBParam("OCCURRED_AT", occurredAt),
                    new DBParam("LIMIT_PER_HOUR", limitPerHour),
                    new DBParam("RETRY_AFTER_MS", retryAfterMs),
                    new DBParam("REASON", reason != null ? reason.trim() : null));
            incident.save();
            return null;
        });
    }

    public List<ThrottleIncident> fetchRecentIncidents(int maxItems) {
        int safeLimit = Math.min(MAX_INCIDENT_SAMPLES, Math.max(1, maxItems));
        long now = System.currentTimeMillis();
        CachedIncidents cached = recentIncidentsCache;
        if (cached != null && cached.limit == safeLimit && cached.expiresAt > now) {
            return cached.samples;
        }
        List<ThrottleIncident> incidents = ao.executeInTransaction(() -> {
            GuardrailsRateIncident[] rows = ao.find(GuardrailsRateIncident.class,
                    Query.select()
                            .order("OCCURRED_AT DESC")
                            .limit(safeLimit));
            if (rows.length == 0) {
                return Collections.emptyList();
            }
            List<ThrottleIncident> result = new ArrayList<>(rows.length);
            for (GuardrailsRateIncident row : rows) {
                result.add(toIncident(row));
            }
            return result;
        });
        recentIncidentsCache = new CachedIncidents(new ArrayList<>(incidents), safeLimit, now + INCIDENT_TTL_MS);
        return incidents;
    }

    public List<IncidentAggregate> aggregateIncidents(GuardrailsRateLimitScope scope,
                                                      long sinceEpochMillis,
                                                      int maxEntries) {
        if (!supportsScope(scope)) {
            return Collections.emptyList();
        }
        long cutoff = Math.max(0, sinceEpochMillis);
        int safeLimit = Math.min(MAX_TOP_BUCKETS, Math.max(1, maxEntries));
        List<GuardrailsRateIncident> rows = ao.executeInTransaction(() -> {
            GuardrailsRateIncident[] found = ao.find(GuardrailsRateIncident.class,
                    Query.select()
                            .where("SCOPE = ? AND OCCURRED_AT >= ?", scope.name(), cutoff)
                            .order("OCCURRED_AT DESC"));
            List<GuardrailsRateIncident> result = new ArrayList<>(found.length);
            Collections.addAll(result, found);
            return result;
        });
        if (rows.isEmpty()) {
            return Collections.emptyList();
        }
        Map<String, IncidentAggregate.Mutable> grouped = rows.stream()
                .collect(Collectors.toMap(
                        row -> identifierKey(scope, row.getIdentifier()),
                        row -> IncidentAggregate.Mutable.from(row),
                        IncidentAggregate.Mutable::merge));
        return grouped.values().stream()
                .sorted(Comparator.comparingInt(IncidentAggregate.Mutable::getCount).reversed())
                .limit(safeLimit)
                .map(IncidentAggregate.Mutable::toValue)
                .collect(Collectors.toList());
    }

    private GuardrailsRateBucket loadOrCreateBucket(GuardrailsRateLimitScope scope,
                                                    String identifier,
                                                    long windowStart,
                                                    long now) {
        String bucketKey = bucketKey(scope, identifier, windowStart);
        GuardrailsRateBucket[] rows = ao.find(GuardrailsRateBucket.class,
                Query.select().where("BUCKET_KEY = ?", bucketKey));
        if (rows.length > 0) {
            return rows[0];
        }
        try {
            return ao.create(GuardrailsRateBucket.class,
                    new DBParam("BUCKET_KEY", bucketKey),
                    new DBParam("SCOPE", scope.name()),
                    new DBParam("IDENTIFIER", identifier),
                    new DBParam("WINDOW_START", windowStart),
                    new DBParam("CONSUMED", 0),
                    new DBParam("LIMIT_PER_HOUR", 0),
                    new DBParam("UPDATED_AT", now));
        } catch (RuntimeException ex) {
            GuardrailsRateBucket[] retry = ao.find(GuardrailsRateBucket.class,
                    Query.select().where("BUCKET_KEY = ?", bucketKey));
            if (retry.length > 0) {
                return retry[0];
            }
            throw ex;
        }
    }

    private WindowSample windowSample(GuardrailsRateLimitScope scope, GuardrailsRateBucket row) {
        return new WindowSample(
                scope,
                row.getIdentifier(),
                row.getWindowStart(),
                row.getConsumed(),
                row.getLimitPerHour(),
                row.getUpdatedAt());
    }

    private ThrottleIncident toIncident(GuardrailsRateIncident row) {
        GuardrailsRateLimitScope scope = GuardrailsRateLimitScope.fromString(row.getScope());
        return new ThrottleIncident(
                scope,
                row.getIdentifier(),
                row.getProjectKey(),
                row.getRepositorySlug(),
                row.getOccurredAt(),
                row.getLimitPerHour(),
                row.getRetryAfterMs(),
                row.getReason());
    }

    private boolean supportsScope(GuardrailsRateLimitScope scope) {
        return scope == GuardrailsRateLimitScope.REPOSITORY || scope == GuardrailsRateLimitScope.PROJECT;
    }

    private String bucketKey(GuardrailsRateLimitScope scope, String identifier, long windowStart) {
        return scope.name() + ":" + identifier + ":" + windowStart;
    }

    private String identifierKey(GuardrailsRateLimitScope scope, String identifier) {
        return scope.name() + ":" + (identifier != null ? identifier : "*");
    }

    private String normalizeIdentifier(String input) {
        if (input == null) {
            return "";
        }
        return input.trim().toLowerCase(Locale.ROOT);
    }

    private String sanitize(@Nullable String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static final class SnapshotCache {
        private final List<WindowSample> samples;
        private final long expiresAt;

        private SnapshotCache(List<WindowSample> samples, long expiresAt) {
            this.samples = samples != null ? samples : Collections.emptyList();
            this.expiresAt = expiresAt;
        }
    }

    private static final class CachedIncidents {
        private final List<ThrottleIncident> samples;
        private final int limit;
        private final long expiresAt;

        private CachedIncidents(List<ThrottleIncident> samples, int limit, long expiresAt) {
            this.samples = samples != null ? samples : Collections.emptyList();
            this.limit = limit;
            this.expiresAt = expiresAt;
        }
    }

    public static final class WindowSample {
        private final GuardrailsRateLimitScope scope;
        private final String identifier;
        private final long windowStart;
        private final int consumed;
        private final int limitPerHour;
        private final long updatedAt;

        public WindowSample(GuardrailsRateLimitScope scope,
                            String identifier,
                            long windowStart,
                            int consumed,
                            int limitPerHour,
                            long updatedAt) {
            this.scope = scope;
            this.identifier = identifier;
            this.windowStart = windowStart;
            this.consumed = consumed;
            this.limitPerHour = limitPerHour;
            this.updatedAt = updatedAt;
        }

        public GuardrailsRateLimitScope getScope() {
            return scope;
        }

        public String getIdentifier() {
            return identifier;
        }

        public long getWindowStart() {
            return windowStart;
        }

        public int getConsumed() {
            return consumed;
        }

        public int getLimitPerHour() {
            return limitPerHour;
        }

        public long getUpdatedAt() {
            return updatedAt;
        }

        public long estimateResetIn(long windowDurationMs, long now) {
            long resetAt = windowStart + windowDurationMs;
            return Math.max(0L, resetAt - now);
        }

        public int getRemaining() {
            return Math.max(0, limitPerHour - consumed);
        }
    }

    public static final class ThrottleIncident {
        private final GuardrailsRateLimitScope scope;
        private final String identifier;
        private final String projectKey;
        private final String repositorySlug;
        private final long occurredAt;
        private final int limitPerHour;
        private final long retryAfterMs;
        private final String reason;

        public ThrottleIncident(GuardrailsRateLimitScope scope,
                                String identifier,
                                String projectKey,
                                String repositorySlug,
                                long occurredAt,
                                int limitPerHour,
                                long retryAfterMs,
                                String reason) {
            this.scope = scope;
            this.identifier = identifier;
            this.projectKey = projectKey;
            this.repositorySlug = repositorySlug;
            this.occurredAt = occurredAt;
            this.limitPerHour = limitPerHour;
            this.retryAfterMs = retryAfterMs;
            this.reason = reason;
        }

        public GuardrailsRateLimitScope getScope() {
            return scope;
        }

        public String getIdentifier() {
            return identifier;
        }

        public String getProjectKey() {
            return projectKey;
        }

        public String getRepositorySlug() {
            return repositorySlug;
        }

        public long getOccurredAt() {
            return occurredAt;
        }

        public int getLimitPerHour() {
            return limitPerHour;
        }

        public long getRetryAfterMs() {
            return retryAfterMs;
        }

        public String getReason() {
            return reason;
        }
    }

    public static final class IncidentAggregate {
        private final GuardrailsRateLimitScope scope;
        private final String identifier;
        private final int throttledCount;
        private final long lastOccurredAt;
        private final long averageRetryAfterMs;
        private final int lastKnownLimit;

        public IncidentAggregate(GuardrailsRateLimitScope scope,
                                 String identifier,
                                 int throttledCount,
                                 long lastOccurredAt,
                                 long averageRetryAfterMs,
                                 int lastKnownLimit) {
            this.scope = scope;
            this.identifier = identifier;
            this.throttledCount = throttledCount;
            this.lastOccurredAt = lastOccurredAt;
            this.averageRetryAfterMs = averageRetryAfterMs;
            this.lastKnownLimit = lastKnownLimit;
        }

        public GuardrailsRateLimitScope getScope() {
            return scope;
        }

        public String getIdentifier() {
            return identifier;
        }

        public int getThrottledCount() {
            return throttledCount;
        }

        public long getLastOccurredAt() {
            return lastOccurredAt;
        }

        public long getAverageRetryAfterMs() {
            return averageRetryAfterMs;
        }

        public int getLastKnownLimit() {
            return lastKnownLimit;
        }

        private static final class Mutable {
            private final GuardrailsRateLimitScope scope;
            private final String identifier;
            private int count;
            private long lastOccurred;
            private long retryAfterSum;
            private int lastLimit;

            private Mutable(GuardrailsRateLimitScope scope,
                            String identifier,
                            int count,
                            long lastOccurred,
                            long retryAfterSum,
                            int lastLimit) {
                this.scope = scope;
                this.identifier = identifier;
                this.count = count;
                this.lastOccurred = lastOccurred;
                this.retryAfterSum = retryAfterSum;
                this.lastLimit = lastLimit;
            }

            static Mutable from(GuardrailsRateIncident row) {
                GuardrailsRateLimitScope scope = GuardrailsRateLimitScope.fromString(row.getScope());
                return new Mutable(
                        scope,
                        row.getIdentifier(),
                        1,
                        row.getOccurredAt(),
                        row.getRetryAfterMs(),
                        row.getLimitPerHour());
            }

            Mutable merge(Mutable other) {
                this.count += other.count;
                this.retryAfterSum += other.retryAfterSum;
                if (other.lastOccurred > this.lastOccurred) {
                    this.lastOccurred = other.lastOccurred;
                    this.lastLimit = other.lastLimit;
                }
                return this;
            }

            int getCount() {
                return count;
            }

            IncidentAggregate toValue() {
                long avgRetry = count > 0 ? retryAfterSum / count : 0;
                return new IncidentAggregate(scope, identifier, count, lastOccurred, avgRetry, lastLimit);
            }
        }
    }
}
