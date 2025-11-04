package com.example.bitbucket.aireviewer.progress;

import com.example.bitbucket.aireviewer.dto.ReviewResult;

import com.atlassian.plugin.spring.scanner.annotation.export.ExportAsService;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.inject.Singleton;
import javax.inject.Named;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

/**
 * Tracks in-flight review progress for quick polling.
 */
@Named
@Singleton
@ExportAsService(ProgressRegistry.class)
public class ProgressRegistry {

    private static final long ACTIVE_TTL_MS = TimeUnit.MINUTES.toMillis(5);
    private static final long COMPLETED_TTL_MS = TimeUnit.MINUTES.toMillis(10);

    private final ConcurrentHashMap<String, ProgressContext> contexts = new ConcurrentHashMap<>();

    public void start(@Nonnull ProgressMetadata metadata) {
        Objects.requireNonNull(metadata, "metadata");
        String key = metadata.key();
        ProgressContext context = new ProgressContext(metadata);
        contexts.put(key, context);
    }

    public void record(@Nonnull ProgressMetadata metadata, @Nonnull ProgressEvent event) {
        Objects.requireNonNull(metadata, "metadata");
        Objects.requireNonNull(event, "event");
        String key = metadata.key();
        contexts.compute(key, (k, existing) -> {
            ProgressContext context = existing;
            if (context == null || !context.matches(metadata)) {
                context = new ProgressContext(metadata);
            }
            context.addEvent(event);
            return context;
        });
    }

    public void complete(@Nonnull ProgressMetadata metadata, @Nonnull ReviewResult.Status status) {
        Objects.requireNonNull(metadata, "metadata");
        Objects.requireNonNull(status, "status");
        ProgressContext context = contexts.get(metadata.key());
        if (context != null && context.matches(metadata)) {
            context.markCompleted(status);
        }
    }

    @Nonnull
    public Optional<ProgressSnapshot> getActive(@Nonnull String projectKey,
                                                @Nonnull String repositorySlug,
                                                long pullRequestId) {
        pruneExpired();
        String key = ProgressMetadata.buildKey(projectKey, repositorySlug, pullRequestId);
        ProgressContext context = contexts.get(key);
        if (context == null || context.isExpired()) {
            if (context != null) {
                contexts.remove(key, context);
            }
            return Optional.empty();
        }
        return Optional.of(context.snapshot());
    }

    private void pruneExpired() {
        long now = System.currentTimeMillis();
        contexts.forEach((key, context) -> {
            if (context.isExpired(now)) {
                contexts.remove(key, context);
            }
        });
    }

    /**
     * Metadata describing an in-flight review run.
     */
    public static final class ProgressMetadata {
        private final String projectKey;
        private final String repositorySlug;
        private final long pullRequestId;
        private final String runId;
        private final boolean manual;
        private final boolean update;
        private final boolean force;

        public ProgressMetadata(@Nonnull String projectKey,
                                @Nonnull String repositorySlug,
                                long pullRequestId,
                                @Nonnull String runId,
                                boolean manual,
                                boolean update,
                                boolean force) {
            this.projectKey = Objects.requireNonNull(projectKey, "projectKey");
            this.repositorySlug = Objects.requireNonNull(repositorySlug, "repositorySlug");
            this.pullRequestId = pullRequestId;
            this.runId = Objects.requireNonNull(runId, "runId");
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

        public String getRunId() {
            return runId;
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

        private String key() {
            return buildKey(projectKey, repositorySlug, pullRequestId);
        }

        private static String buildKey(String projectKey, String repoSlug, long pullRequestId) {
            return projectKey + "/" + repoSlug + "#" + pullRequestId;
        }
    }

    /**
     * Immutable snapshot model for REST responses.
     */
    public static final class ProgressSnapshot {
        private final ProgressMetadata metadata;
        private final List<ProgressEvent> events;
        private final boolean completed;
        private final ReviewResult.Status finalStatus;
        private final long startedAt;
        private final long lastUpdatedAt;
        private final long completedAt;
        private final int eventCount;

        private ProgressSnapshot(ProgressMetadata metadata,
                                 List<ProgressEvent> events,
                                 boolean completed,
                                 ReviewResult.Status finalStatus,
                                 long startedAt,
                                 long lastUpdatedAt,
                                 long completedAt,
                                 int eventCount) {
            this.metadata = metadata;
            this.events = Collections.unmodifiableList(events);
            this.completed = completed;
            this.finalStatus = finalStatus;
            this.startedAt = startedAt;
            this.lastUpdatedAt = lastUpdatedAt;
            this.completedAt = completedAt;
            this.eventCount = Math.max(eventCount, 0);
        }

        public ProgressMetadata getMetadata() {
            return metadata;
        }

        public List<ProgressEvent> getEvents() {
            return events;
        }

        public boolean isCompleted() {
            return completed;
        }

        public ReviewResult.Status getFinalStatus() {
            return finalStatus;
        }

        public long getStartedAt() {
            return startedAt;
        }

        public long getLastUpdatedAt() {
            return lastUpdatedAt;
        }

        public long getCompletedAt() {
            return completedAt;
        }

        public int getEventCount() {
            return eventCount;
        }

        public String getState() {
            if (completed) {
                return finalStatus != null ? finalStatus.getValue() : "completed";
            }
            return "in_progress";
        }
    }

    private static final class ProgressContext {
        private final ProgressMetadata metadata;
        private final CopyOnWriteArrayList<ProgressEvent> events = new CopyOnWriteArrayList<>();
        private final long startedAt;
        private volatile long lastUpdatedAt;
        private volatile boolean completed;
        private volatile ReviewResult.Status finalStatus;
        private volatile long completedAt;

        ProgressContext(ProgressMetadata metadata) {
            this.metadata = metadata;
            this.startedAt = System.currentTimeMillis();
            this.lastUpdatedAt = this.startedAt;
        }

        boolean matches(ProgressMetadata other) {
            return metadata.getRunId().equals(other.getRunId());
        }

        void addEvent(ProgressEvent event) {
            events.add(event);
            lastUpdatedAt = System.currentTimeMillis();
        }

        void markCompleted(ReviewResult.Status status) {
            this.completed = true;
            this.finalStatus = status;
            long now = System.currentTimeMillis();
            this.completedAt = now;
            lastUpdatedAt = now;
        }

        boolean isExpired() {
            return isExpired(System.currentTimeMillis());
        }

        boolean isExpired(long now) {
            long ttl = completed ? COMPLETED_TTL_MS : ACTIVE_TTL_MS;
            return now - lastUpdatedAt > ttl;
        }

        ProgressSnapshot snapshot() {
            return new ProgressSnapshot(
                    metadata,
                    new ArrayList<>(events),
                    completed,
                    finalStatus,
                    startedAt,
                    lastUpdatedAt,
                    completedAt,
                    events.size());
        }
    }

    /**
     * Utility factory primarily for tests.
     */
    @Nonnull
    public static ProgressSnapshot createSnapshot(@Nonnull ProgressMetadata metadata,
                                                  @Nonnull List<ProgressEvent> events,
                                                  boolean completed,
                                                  @Nullable ReviewResult.Status finalStatus,
                                                  long startedAt,
                                                  long lastUpdatedAt) {
        long computedCompletedAt = completed ? lastUpdatedAt : 0L;
        return createSnapshot(metadata, events, completed, finalStatus, startedAt, lastUpdatedAt, computedCompletedAt);
    }

    @Nonnull
    public static ProgressSnapshot createSnapshot(@Nonnull ProgressMetadata metadata,
                                                  @Nonnull List<ProgressEvent> events,
                                                  boolean completed,
                                                  @Nullable ReviewResult.Status finalStatus,
                                                  long startedAt,
                                                  long lastUpdatedAt,
                                                  long completedAt) {
        return new ProgressSnapshot(
                metadata,
                new ArrayList<>(events),
                completed,
                finalStatus,
                startedAt,
                lastUpdatedAt,
                completedAt,
                events.size());
    }
}
