package com.teknolojikpanda.bitbucket.aireviewer.service;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * Coordinates off-peak cleanup maintenance by executing multiple small AO delete batches within a window.
 */
@Named
@Singleton
public class ReviewHistoryMaintenanceService {

    private final ReviewHistoryCleanupService cleanupService;
    private final ZoneId zoneId;

    @Inject
    public ReviewHistoryMaintenanceService(ReviewHistoryCleanupService cleanupService) {
        this(cleanupService, ZoneId.systemDefault());
    }

    ReviewHistoryMaintenanceService(ReviewHistoryCleanupService cleanupService, ZoneId zoneId) {
        this.cleanupService = Objects.requireNonNull(cleanupService, "cleanupService");
        this.zoneId = Objects.requireNonNull(zoneId, "zoneId");
    }

    public boolean isWithinWindow(ReviewHistoryCleanupStatusService.Status status, long epochMillis) {
        Window window = resolveWindow(status, epochMillis);
        if (!window.isValid()) {
            return false;
        }
        return epochMillis >= window.startEpochMillis && epochMillis <= window.endEpochMillis;
    }

    /**
     * Computes the next window start (in epoch millis) that occurs on or after the supplied timestamp.
     */
    public long nextWindowStartMillis(ReviewHistoryCleanupStatusService.Status status, long epochMillis) {
        ZonedDateTime reference = Instant.ofEpochMilli(epochMillis).atZone(zoneId);
        ZonedDateTime candidate = reference
                .withHour(status.getWindowStartHour())
                .withMinute(0)
                .withSecond(0)
                .withNano(0);
        if (!candidate.isAfter(reference)) {
            candidate = candidate.plusDays(1);
        }
        return candidate.toInstant().toEpochMilli();
    }

    public long computeNextScheduleTime(ReviewHistoryCleanupStatusService.Status status, long epochMillis) {
        if (isWithinWindow(status, epochMillis)) {
            return epochMillis + 2000L;
        }
        return nextWindowStartMillis(status, epochMillis);
    }

    public MaintenanceRun runMaintenanceWindow(ReviewHistoryCleanupStatusService.Status status) {
        long windowStart = System.currentTimeMillis();
        long deadline = windowStart + TimeUnit.MINUTES.toMillis(Math.max(30, status.getWindowDurationMinutes()));
        int maxBatches = Math.max(1, status.getMaxBatchesPerWindow());
        int executedBatches = 0;
        int totalDeletedHistories = 0;
        int totalDeletedChunks = 0;
        ReviewHistoryCleanupService.CleanupResult lastResult = null;
        while (System.currentTimeMillis() < deadline && executedBatches < maxBatches) {
            ReviewHistoryCleanupService.CleanupResult result =
                    cleanupService.cleanupOlderThanDays(status.getRetentionDays(), status.getBatchSize());
            executedBatches++;
            totalDeletedHistories += result.getDeletedHistories();
            totalDeletedChunks += result.getDeletedChunks();
            lastResult = result;
            if (result.getDeletedHistories() < status.getBatchSize()) {
                break;
            }
        }
        long elapsedMs = Math.max(0, System.currentTimeMillis() - windowStart);
        long cutoff = lastResult != null
                ? lastResult.getCutoffEpochMs()
                : windowStart - TimeUnit.DAYS.toMillis(status.getRetentionDays());
        int remaining = lastResult != null ? lastResult.getRemainingCandidates() : 0;
        double throughput = elapsedMs > 0 ? (totalDeletedHistories / (elapsedMs / 1000d)) : totalDeletedHistories;
        ReviewHistoryCleanupService.CleanupResult aggregate =
                new ReviewHistoryCleanupService.CleanupResult(
                        status.getRetentionDays(),
                        status.getBatchSize(),
                        totalDeletedHistories,
                        totalDeletedChunks,
                        remaining,
                        cutoff,
                        elapsedMs,
                        throughput,
                        Math.max(1, executedBatches));
        return new MaintenanceRun(aggregate, executedBatches, totalDeletedHistories, totalDeletedChunks);
    }

    private Window resolveWindow(ReviewHistoryCleanupStatusService.Status status, long epochMillis) {
        long durationMs = TimeUnit.MINUTES.toMillis(Math.max(30, status.getWindowDurationMinutes()));
        ZonedDateTime now = Instant.ofEpochMilli(epochMillis).atZone(zoneId);
        ZonedDateTime todayStart = now.withHour(status.getWindowStartHour())
                .withMinute(0)
                .withSecond(0)
                .withNano(0);
        ZonedDateTime todayEnd = todayStart.plusMinutes(Math.max(30, status.getWindowDurationMinutes()));
        if (!now.isBefore(todayStart) && now.isBefore(todayEnd)) {
            return new Window(todayStart.toInstant().toEpochMilli(), todayEnd.toInstant().toEpochMilli());
        }
        ZonedDateTime yesterdayStart = todayStart.minusDays(1);
        ZonedDateTime yesterdayEnd = yesterdayStart.plusMinutes(Math.max(30, status.getWindowDurationMinutes()));
        if (!now.isBefore(yesterdayStart) && now.isBefore(yesterdayEnd)) {
            return new Window(yesterdayStart.toInstant().toEpochMilli(), yesterdayEnd.toInstant().toEpochMilli());
        }
        // Not inside window; return upcoming slot info but flagged invalid.
        long nextStart = nextWindowStartMillis(status, epochMillis);
        return new Window(nextStart, nextStart + durationMs, false);
    }

    public static final class MaintenanceRun {
        private final ReviewHistoryCleanupService.CleanupResult aggregatedResult;
        private final int batchesExecuted;
        private final int deletedHistories;
        private final int deletedChunks;

        MaintenanceRun(ReviewHistoryCleanupService.CleanupResult aggregatedResult,
                       int batchesExecuted,
                       int deletedHistories,
                       int deletedChunks) {
            this.aggregatedResult = aggregatedResult;
            this.batchesExecuted = batchesExecuted;
            this.deletedHistories = deletedHistories;
            this.deletedChunks = deletedChunks;
        }

        public ReviewHistoryCleanupService.CleanupResult getAggregatedResult() {
            return aggregatedResult;
        }

        public int getBatchesExecuted() {
            return batchesExecuted;
        }

        public int getDeletedHistories() {
            return deletedHistories;
        }

        public int getDeletedChunks() {
            return deletedChunks;
        }
    }

    private static final class Window {
        private final long startEpochMillis;
        private final long endEpochMillis;
        private final boolean valid;

        private Window(long startEpochMillis, long endEpochMillis) {
            this(startEpochMillis, endEpochMillis, true);
        }

        private Window(long startEpochMillis, long endEpochMillis, boolean valid) {
            this.startEpochMillis = startEpochMillis;
            this.endEpochMillis = endEpochMillis;
            this.valid = valid;
        }

        private boolean isValid() {
            return valid;
        }
    }
}
