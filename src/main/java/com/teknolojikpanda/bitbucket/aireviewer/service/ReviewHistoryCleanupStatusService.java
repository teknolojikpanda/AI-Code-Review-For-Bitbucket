package com.teknolojikpanda.bitbucket.aireviewer.service;

import com.atlassian.activeobjects.external.ActiveObjects;
import com.atlassian.plugin.spring.scanner.annotation.imports.ComponentImport;
import com.teknolojikpanda.bitbucket.aireviewer.ao.AIReviewCleanupStatus;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;
import java.util.Objects;

@Named
@Singleton
public class ReviewHistoryCleanupStatusService {

    private static final int DEFAULT_RETENTION_DAYS = 90;
    private static final int DEFAULT_BATCH_SIZE = 200;
    private static final int DEFAULT_INTERVAL_MINUTES = 60 * 24;
    private static final int DEFAULT_WINDOW_START_HOUR = 2;
    private static final int DEFAULT_WINDOW_DURATION_MINUTES = 180;
    private static final int DEFAULT_MAX_BATCHES = 6;

    private final ActiveObjects ao;

    @Inject
    public ReviewHistoryCleanupStatusService(@ComponentImport ActiveObjects ao) {
        this.ao = Objects.requireNonNull(ao, "activeObjects");
    }

    public Status getStatus() {
        return ao.executeInTransaction(() -> {
            AIReviewCleanupStatus[] rows = ao.find(AIReviewCleanupStatus.class);
            AIReviewCleanupStatus entity = rows.length > 0 ? rows[0] : createDefault();
            return toStatus(entity);
        });
    }

    public void updateSchedule(int retentionDays, int batchSize, int intervalMinutes, boolean enabled) {
        Status current = getStatus();
        updateSchedule(retentionDays,
                batchSize,
                intervalMinutes,
                enabled,
                current.getWindowStartHour(),
                current.getWindowDurationMinutes(),
                current.getMaxBatchesPerWindow());
    }

    public void updateSchedule(int retentionDays,
                               int batchSize,
                               int intervalMinutes,
                               boolean enabled,
                               int windowStartHour,
                               int windowDurationMinutes,
                               int maxBatchesPerWindow) {
        ao.executeInTransaction(() -> {
            AIReviewCleanupStatus entity = loadOrCreate();
            entity.setRetentionDays(Math.max(1, retentionDays));
            entity.setBatchSize(Math.max(1, batchSize));
            entity.setIntervalMinutes(Math.max(5, intervalMinutes));
            entity.setWindowStartHour(normalizeHour(windowStartHour));
            entity.setWindowDurationMinutes(Math.max(30, windowDurationMinutes));
            entity.setMaxBatchesPerWindow(Math.max(1, maxBatchesPerWindow));
            entity.setEnabled(enabled);
            entity.save();
            return null;
        });
    }

    public void recordRun(ReviewHistoryCleanupService.CleanupResult result) {
        ao.executeInTransaction(() -> {
            AIReviewCleanupStatus entity = loadOrCreate();
            entity.setLastRun(System.currentTimeMillis());
            entity.setLastDurationMs(Math.max(0, result.getElapsedMs()));
            entity.setLastDeletedHistories(result.getDeletedHistories());
            entity.setLastDeletedChunks(result.getDeletedChunks());
            entity.setLastBatchesExecuted(result.getBatchesExecuted());
            entity.setLastError(null);
            entity.save();
            return null;
        });
    }

    public void recordFailure(String message) {
        ao.executeInTransaction(() -> {
            AIReviewCleanupStatus entity = loadOrCreate();
            entity.setLastRun(System.currentTimeMillis());
            entity.setLastError(trim(message));
            entity.save();
            return null;
        });
    }

    private AIReviewCleanupStatus createDefault() {
        return ao.create(AIReviewCleanupStatus.class,
                new net.java.ao.DBParam("RETENTION_DAYS", DEFAULT_RETENTION_DAYS),
                new net.java.ao.DBParam("BATCH_SIZE", DEFAULT_BATCH_SIZE),
                new net.java.ao.DBParam("INTERVAL_MINUTES", DEFAULT_INTERVAL_MINUTES),
                new net.java.ao.DBParam("WINDOW_START_HOUR", DEFAULT_WINDOW_START_HOUR),
                new net.java.ao.DBParam("WINDOW_DURATION_MINUTES", DEFAULT_WINDOW_DURATION_MINUTES),
                new net.java.ao.DBParam("MAX_BATCHES_PER_WINDOW", DEFAULT_MAX_BATCHES),
                new net.java.ao.DBParam("LAST_BATCHES_EXECUTED", 0),
                new net.java.ao.DBParam("ENABLED", true));
    }

    private AIReviewCleanupStatus loadOrCreate() {
        AIReviewCleanupStatus[] rows = ao.find(AIReviewCleanupStatus.class);
        if (rows.length > 0) {
            return rows[0];
        }
        return createDefault();
    }

    private Status toStatus(AIReviewCleanupStatus entity) {
        WindowSpec spec = resolveWindowSpec(entity);
        return new Status(
                entity.isEnabled(),
                entity.getRetentionDays(),
                entity.getBatchSize(),
                entity.getIntervalMinutes(),
                spec.windowStartHour,
                spec.windowDurationMinutes,
                spec.maxBatchesPerWindow,
                entity.getLastRun(),
                entity.getLastDurationMs(),
                entity.getLastDeletedHistories(),
                entity.getLastDeletedChunks(),
                entity.getLastBatchesExecuted(),
                entity.getLastError());
    }

    private String trim(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        return trimmed.substring(0, Math.min(trimmed.length(), 4000));
    }

    public static final class Status {
        private final boolean enabled;
        private final int retentionDays;
        private final int batchSize;
        private final int intervalMinutes;
        private final int windowStartHour;
        private final int windowDurationMinutes;
        private final int maxBatchesPerWindow;
        private final long lastRun;
        private final long lastDurationMs;
        private final int lastDeletedHistories;
        private final int lastDeletedChunks;
        private final int lastBatchesExecuted;
        private final String lastError;

        Status(boolean enabled,
               int retentionDays,
               int batchSize,
               int intervalMinutes,
               int windowStartHour,
               int windowDurationMinutes,
               int maxBatchesPerWindow,
               long lastRun,
               long lastDurationMs,
               int lastDeletedHistories,
               int lastDeletedChunks,
               int lastBatchesExecuted,
               String lastError) {
            this.enabled = enabled;
            this.retentionDays = retentionDays;
            this.batchSize = batchSize;
            this.intervalMinutes = intervalMinutes;
            this.windowStartHour = windowStartHour;
            this.windowDurationMinutes = windowDurationMinutes;
            this.maxBatchesPerWindow = maxBatchesPerWindow;
            this.lastRun = lastRun;
            this.lastDurationMs = lastDurationMs;
            this.lastDeletedHistories = lastDeletedHistories;
            this.lastDeletedChunks = lastDeletedChunks;
            this.lastBatchesExecuted = lastBatchesExecuted;
            this.lastError = lastError;
        }

        public static Status snapshot(boolean enabled,
                                       int retentionDays,
                                       int batchSize,
                                       int intervalMinutes,
                                       int windowStartHour,
                                       int windowDurationMinutes,
                                       int maxBatchesPerWindow,
                                       long lastRun,
                                       long lastDurationMs,
                                       int lastDeletedHistories,
                                       int lastDeletedChunks,
                                       int lastBatchesExecuted,
                                       String lastError) {
            return new Status(enabled,
                    retentionDays,
                    batchSize,
                    intervalMinutes,
                    windowStartHour,
                    windowDurationMinutes,
                    maxBatchesPerWindow,
                    lastRun,
                    lastDurationMs,
                    lastDeletedHistories,
                    lastDeletedChunks,
                    lastBatchesExecuted,
                    lastError);
        }

        public static Status snapshot(boolean enabled,
                                      int retentionDays,
                                      int batchSize,
                                      int intervalMinutes,
                                      long lastRun,
                                      long lastDurationMs,
                                      int lastDeletedHistories,
                                      int lastDeletedChunks,
                                      String lastError) {
            return snapshot(enabled,
                    retentionDays,
                    batchSize,
                    intervalMinutes,
                    DEFAULT_WINDOW_START_HOUR,
                    DEFAULT_WINDOW_DURATION_MINUTES,
                    DEFAULT_MAX_BATCHES,
                    lastRun,
                    lastDurationMs,
                    lastDeletedHistories,
                    lastDeletedChunks,
                    0,
                    lastError);
        }

        public boolean isEnabled() {
            return enabled;
        }

        public int getRetentionDays() {
            return retentionDays;
        }

        public int getBatchSize() {
            return batchSize;
        }

        public int getIntervalMinutes() {
            return intervalMinutes;
        }

        public int getWindowStartHour() {
            return windowStartHour;
        }

        public int getWindowDurationMinutes() {
            return windowDurationMinutes;
        }

        public int getMaxBatchesPerWindow() {
            return maxBatchesPerWindow;
        }

        public long getLastRun() {
            return lastRun;
        }

        public long getLastDurationMs() {
            return lastDurationMs;
        }

        public int getLastDeletedHistories() {
            return lastDeletedHistories;
        }

        public int getLastDeletedChunks() {
            return lastDeletedChunks;
        }

        public int getLastBatchesExecuted() {
            return lastBatchesExecuted;
        }

        public String getLastError() {
            return lastError;
        }
    }

    private int normalizeHour(int hour) {
        if (hour < 0) {
            return 0;
        }
        if (hour > 23) {
            return hour % 24;
        }
        return hour;
    }

    private WindowSpec resolveWindowSpec(AIReviewCleanupStatus entity) {
        int start = entity.getWindowStartHour();
        int duration = entity.getWindowDurationMinutes();
        int batches = entity.getMaxBatchesPerWindow();
        boolean dirty = false;
        if (start < 0 || start > 23) {
            start = DEFAULT_WINDOW_START_HOUR;
            dirty = true;
        }
        if (duration <= 0) {
            duration = DEFAULT_WINDOW_DURATION_MINUTES;
            dirty = true;
        } else if (duration < 30) {
            duration = 30;
            dirty = true;
        }
        if (batches <= 0) {
            batches = DEFAULT_MAX_BATCHES;
            dirty = true;
        }
        start = normalizeHour(start);
        if (dirty) {
            entity.setWindowStartHour(start);
            entity.setWindowDurationMinutes(duration);
            entity.setMaxBatchesPerWindow(batches);
            entity.save();
        }
        return new WindowSpec(start, duration, batches);
    }

    private static final class WindowSpec {
        private final int windowStartHour;
        private final int windowDurationMinutes;
        private final int maxBatchesPerWindow;

        private WindowSpec(int windowStartHour, int windowDurationMinutes, int maxBatchesPerWindow) {
            this.windowStartHour = windowStartHour;
            this.windowDurationMinutes = windowDurationMinutes;
            this.maxBatchesPerWindow = maxBatchesPerWindow;
        }
    }
}
