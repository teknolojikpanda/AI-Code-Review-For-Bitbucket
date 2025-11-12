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
        ao.executeInTransaction(() -> {
            AIReviewCleanupStatus entity = loadOrCreate();
            entity.setRetentionDays(Math.max(1, retentionDays));
            entity.setBatchSize(Math.max(1, batchSize));
            entity.setIntervalMinutes(Math.max(5, intervalMinutes));
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
        return new Status(
                entity.isEnabled(),
                entity.getRetentionDays(),
                entity.getBatchSize(),
                entity.getIntervalMinutes(),
                entity.getLastRun(),
                entity.getLastDurationMs(),
                entity.getLastDeletedHistories(),
                entity.getLastDeletedChunks(),
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
        private final long lastRun;
        private final long lastDurationMs;
        private final int lastDeletedHistories;
        private final int lastDeletedChunks;
        private final String lastError;

        Status(boolean enabled,
               int retentionDays,
               int batchSize,
               int intervalMinutes,
               long lastRun,
               long lastDurationMs,
               int lastDeletedHistories,
               int lastDeletedChunks,
               String lastError) {
            this.enabled = enabled;
            this.retentionDays = retentionDays;
            this.batchSize = batchSize;
            this.intervalMinutes = intervalMinutes;
            this.lastRun = lastRun;
            this.lastDurationMs = lastDurationMs;
            this.lastDeletedHistories = lastDeletedHistories;
            this.lastDeletedChunks = lastDeletedChunks;
            this.lastError = lastError;
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
            return new Status(enabled, retentionDays, batchSize, intervalMinutes, lastRun, lastDurationMs, lastDeletedHistories, lastDeletedChunks, lastError);
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

        public String getLastError() {
            return lastError;
        }
    }
}
