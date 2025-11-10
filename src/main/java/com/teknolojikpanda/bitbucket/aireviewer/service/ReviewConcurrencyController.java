package com.teknolojikpanda.bitbucket.aireviewer.service;

import com.atlassian.plugin.spring.scanner.annotation.export.ExportAsService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
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
    private static final int DEFAULT_MAX_QUEUE_PER_REPO = 5;
    private static final int DEFAULT_MAX_QUEUE_PER_PROJECT = 15;
    private static final int TOP_SCOPE_SAMPLE_LIMIT = 5;
    private static final long REFRESH_INTERVAL_MS = TimeUnit.SECONDS.toMillis(15);

    private final AIReviewerConfigService configService;
    private final ReviewSchedulerStateService schedulerStateService;
    private final AdjustableSemaphore semaphore;
    private final AtomicInteger waitingCount = new AtomicInteger();
    private final ConcurrentHashMap<String, AtomicInteger> waitingByRepo = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicInteger> waitingByProject = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, QueuedPermit> waitingByRunId = new ConcurrentHashMap<>();

    private volatile int maxConcurrent;
    private volatile int maxQueueSize;
    private volatile int maxQueuedPerRepo;
    private volatile int maxQueuedPerProject;
    private volatile long lastRefreshTimestamp;

    private static final Logger log = LoggerFactory.getLogger(ReviewConcurrencyController.class);

    @Inject
    public ReviewConcurrencyController(AIReviewerConfigService configService,
                                       ReviewSchedulerStateService schedulerStateService) {
        this.configService = Objects.requireNonNull(configService, "configService");
        this.schedulerStateService = Objects.requireNonNull(schedulerStateService, "schedulerStateService");
        this.maxConcurrent = DEFAULT_MAX_CONCURRENT;
        this.maxQueueSize = DEFAULT_MAX_QUEUE;
        this.maxQueuedPerRepo = DEFAULT_MAX_QUEUE_PER_REPO;
        this.maxQueuedPerProject = DEFAULT_MAX_QUEUE_PER_PROJECT;
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
        QueuedPermit permit = registerQueuedWaiter(request);
        if (permit == null) {
            throw new ReviewQueueFullException(buildQueueMessage(request), maxConcurrent, maxQueueSize, waitingCount.get());
        }
        try {
            semaphore.acquire();
            permit.release();
            return new Slot();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            permit.release();
            throw new ReviewSchedulingInterruptedException("Interrupted while waiting for AI review capacity", e);
        } catch (RuntimeException | Error ex) {
            permit.release();
            throw ex;
        } catch (Exception ex) {
            permit.release();
            throw new RuntimeException(ex);
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
        List<QueueStats.ScopeQueueStats> repoWaiters = topScopes(waitingByRepo, TOP_SCOPE_SAMPLE_LIMIT, maxQueuedPerRepo);
        List<QueueStats.ScopeQueueStats> projectWaiters = topScopes(waitingByProject, TOP_SCOPE_SAMPLE_LIMIT, maxQueuedPerProject);
        return new QueueStats(
                maxConcurrent,
                maxQueueSize,
                getActiveReviews(),
                Math.max(0, waitingCount.get()),
                System.currentTimeMillis(),
                state,
                maxQueuedPerRepo,
                maxQueuedPerProject,
                repoWaiters,
                projectWaiters);
    }

    public List<QueueStats.QueueEntry> getQueuedRequests() {
        List<QueueStats.QueueEntry> entries = new ArrayList<>();
        waitingByRunId.forEach((runId, permit) -> {
            ReviewExecutionRequest req = permit.getRequest();
            int repoWaiting = permit.getRepoCounter() != null ? permit.getRepoCounter().get() : 0;
            int projectWaiting = permit.getProjectCounter() != null ? permit.getProjectCounter().get() : 0;
            entries.add(new QueueStats.QueueEntry(
                    runId,
                    req.getProjectKey(),
                    req.getRepositorySlug(),
                    req.getPullRequestId(),
                    req.isManual(),
                    req.isUpdate(),
                    req.isForce(),
                    permit.getWaitingSince(),
                    repoWaiting,
                    projectWaiting));
        });
        entries.sort(Comparator.comparingLong(QueueStats.QueueEntry::getWaitingSince));
        return entries;
    }

    public boolean cancelQueuedRun(@Nonnull String runId) {
        Objects.requireNonNull(runId, "runId");
        QueuedPermit permit = waitingByRunId.remove(runId);
        if (permit == null) {
            return false;
        }
        permit.release();
        log.info("Canceled queued AI review run {} for {}/{} PR #{}", runId,
                permit.getRequest().getProjectKey(),
                permit.getRequest().getRepositorySlug(),
                permit.getRequest().getPullRequestId());
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
            int desiredPerRepo = resolveInt(map.get("maxQueuedPerRepo"), DEFAULT_MAX_QUEUE_PER_REPO, 0, 200);
            int desiredPerProject = resolveInt(map.get("maxQueuedPerProject"), DEFAULT_MAX_QUEUE_PER_PROJECT, 0, 500);
            if (desiredConcurrent != this.maxConcurrent) {
                adjustSemaphore(desiredConcurrent - this.maxConcurrent);
                this.maxConcurrent = desiredConcurrent;
            }
            this.maxQueueSize = desiredQueue;
            this.maxQueuedPerRepo = desiredPerRepo;
            this.maxQueuedPerProject = desiredPerProject;
            this.lastRefreshTimestamp = now;
        }
    }

    private QueuedPermit registerQueuedWaiter(ReviewExecutionRequest request) {
        int globalQueued = waitingCount.incrementAndGet();
        String repoKey = normalizeKey(request.getRepositorySlug(), "__repo__");
        String projectKey = normalizeKey(request.getProjectKey(), "__project__");
        AtomicInteger repoCounter = incrementCounter(waitingByRepo, repoKey);
        AtomicInteger projectCounter = incrementCounter(waitingByProject, projectKey);

        boolean withinGlobal = globalQueued <= maxQueueSize;
        boolean withinRepo = withinLimit(repoCounter, maxQueuedPerRepo);
        boolean withinProject = withinLimit(projectCounter, maxQueuedPerProject);

        if (!(withinGlobal && withinRepo && withinProject)) {
            releaseCounter(waitingByRepo, repoKey, repoCounter);
            releaseCounter(waitingByProject, projectKey, projectCounter);
            waitingCount.decrementAndGet();
            return null;
        }

        String runId = resolveRunId(request);
        long waitingSince = System.currentTimeMillis();
        QueuedPermit permit = new QueuedPermit(runId, request, repoKey, repoCounter, projectKey, projectCounter, waitingSince);
        waitingByRunId.put(runId, permit);
        return permit;
    }

    private AtomicInteger incrementCounter(ConcurrentHashMap<String, AtomicInteger> map, String key) {
        if (key == null) {
            return null;
        }
        return map.compute(key, (k, counter) -> {
            if (counter == null) {
                counter = new AtomicInteger();
            }
            counter.incrementAndGet();
            return counter;
        });
    }

    private void releaseCounter(ConcurrentHashMap<String, AtomicInteger> map,
                                String key,
                                AtomicInteger counter) {
        if (key == null || counter == null) {
            return;
        }
        int remaining = counter.decrementAndGet();
        if (remaining <= 0) {
            map.remove(key, counter);
        }
    }

    private boolean withinLimit(AtomicInteger counter, int limit) {
        if (limit <= 0 || counter == null) {
            return true;
        }
        return counter.get() <= limit;
    }

    private String normalizeKey(String value, String fallback) {
        if (value == null || value.trim().isEmpty()) {
            return fallback;
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }

    private String resolveRunId(ReviewExecutionRequest request) {
        String runId = request.getRunId();
        if (runId != null && !runId.trim().isEmpty()) {
            return runId.trim();
        }
        return String.format("%s/%s#%d-%d",
                normalizeKey(request.getProjectKey(), "proj"),
                normalizeKey(request.getRepositorySlug(), "repo"),
                request.getPullRequestId(),
                System.nanoTime());
    }


    private List<QueueStats.ScopeQueueStats> topScopes(ConcurrentHashMap<String, AtomicInteger> map,
                                                       int limit,
                                                       int configuredLimit) {
        if (map.isEmpty()) {
            return Collections.emptyList();
        }
        List<QueueStats.ScopeQueueStats> items = new ArrayList<>();
        map.forEach((key, counter) -> {
            int waiting = counter.get();
            if (waiting > 0) {
                items.add(new QueueStats.ScopeQueueStats(key, waiting, configuredLimit));
            }
        });
        if (items.isEmpty()) {
            return Collections.emptyList();
        }
        items.sort(Comparator.comparingInt(QueueStats.ScopeQueueStats::getWaiting).reversed());
        if (items.size() > limit) {
            return new ArrayList<>(items.subList(0, limit));
        }
        return items;
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
        private final String runId;

        public ReviewExecutionRequest(String projectKey,
                                      String repositorySlug,
                                      long pullRequestId,
                                      boolean manual,
                                      boolean update,
                                      boolean force,
                                      String runId) {
            this.projectKey = projectKey;
            this.repositorySlug = repositorySlug;
            this.pullRequestId = pullRequestId;
            this.manual = manual;
            this.update = update;
            this.force = force;
            this.runId = runId;
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

        public String getRunId() {
            return runId;
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
        private final int maxQueuedPerRepo;
        private final int maxQueuedPerProject;
        private final List<ScopeQueueStats> topRepoWaiters;
        private final List<ScopeQueueStats> topProjectWaiters;

        public QueueStats(int maxConcurrent,
                          int maxQueued,
                          int active,
                          int waiting,
                          long capturedAt,
                          ReviewSchedulerStateService.SchedulerState schedulerState,
                          int maxQueuedPerRepo,
                          int maxQueuedPerProject,
                          List<ScopeQueueStats> topRepoWaiters,
                          List<ScopeQueueStats> topProjectWaiters) {
            this.maxConcurrent = maxConcurrent;
            this.maxQueued = maxQueued;
            this.active = active;
            this.waiting = waiting;
            this.capturedAt = capturedAt;
            this.schedulerState = schedulerState;
            this.maxQueuedPerRepo = maxQueuedPerRepo;
            this.maxQueuedPerProject = maxQueuedPerProject;
            this.topRepoWaiters = topRepoWaiters != null ? topRepoWaiters : Collections.emptyList();
            this.topProjectWaiters = topProjectWaiters != null ? topProjectWaiters : Collections.emptyList();
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

        public int getMaxQueuedPerRepo() {
            return maxQueuedPerRepo;
        }

        public int getMaxQueuedPerProject() {
            return maxQueuedPerProject;
        }

        public List<ScopeQueueStats> getTopRepoWaiters() {
            return topRepoWaiters;
        }

        public List<ScopeQueueStats> getTopProjectWaiters() {
            return topProjectWaiters;
        }

        public static final class QueueEntry {
            private final String runId;
            private final String projectKey;
            private final String repositorySlug;
            private final long pullRequestId;
            private final boolean manual;
            private final boolean update;
            private final boolean force;
            private final long waitingSince;
            private final int repoWaiting;
            private final int projectWaiting;

            public QueueEntry(String runId,
                              String projectKey,
                              String repositorySlug,
                              long pullRequestId,
                              boolean manual,
                              boolean update,
                              boolean force,
                              long waitingSince,
                              int repoWaiting,
                              int projectWaiting) {
                this.runId = runId;
                this.projectKey = projectKey;
                this.repositorySlug = repositorySlug;
                this.pullRequestId = pullRequestId;
                this.manual = manual;
                this.update = update;
                this.force = force;
                this.waitingSince = waitingSince;
                this.repoWaiting = repoWaiting;
                this.projectWaiting = projectWaiting;
            }

            public String getRunId() {
                return runId;
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

            public long getWaitingSince() {
                return waitingSince;
            }

            public int getRepoWaiting() {
                return repoWaiting;
            }

            public int getProjectWaiting() {
                return projectWaiting;
            }
        }

        public static final class ScopeQueueStats {
            private final String scope;
            private final int waiting;
            private final int limit;

            public ScopeQueueStats(String scope, int waiting, int limit) {
                this.scope = scope;
                this.waiting = waiting;
                this.limit = limit;
            }

            public String getScope() {
                return scope;
            }

            public int getWaiting() {
                return waiting;
            }

            public int getLimit() {
                return limit;
            }
        }
    }

    private final class QueuedPermit {
        private final String runId;
        private final ReviewExecutionRequest request;
        private final String repoKey;
        private final AtomicInteger repoCounter;
        private final String projectKey;
        private final AtomicInteger projectCounter;
        private final long waitingSince;
        private boolean released;

        private QueuedPermit(String runId,
                             ReviewExecutionRequest request,
                             String repoKey,
                             AtomicInteger repoCounter,
                             String projectKey,
                             AtomicInteger projectCounter,
                             long waitingSince) {
            this.runId = runId;
            this.request = request;
            this.repoKey = repoKey;
            this.repoCounter = repoCounter;
            this.projectKey = projectKey;
            this.projectCounter = projectCounter;
            this.waitingSince = waitingSince;
        }

        void release() {
            if (released) {
                return;
            }
            released = true;
            waitingCount.updateAndGet(current -> current > 0 ? current - 1 : 0);
            releaseCounter(waitingByRepo, repoKey, repoCounter);
            releaseCounter(waitingByProject, projectKey, projectCounter);
            waitingByRunId.remove(runId, this);
        }

        String getRunId() {
            return runId;
        }

        ReviewExecutionRequest getRequest() {
            return request;
        }

        long getWaitingSince() {
            return waitingSince;
        }

        AtomicInteger getRepoCounter() {
            return repoCounter;
        }

        AtomicInteger getProjectCounter() {
            return projectCounter;
        }
    }
}
