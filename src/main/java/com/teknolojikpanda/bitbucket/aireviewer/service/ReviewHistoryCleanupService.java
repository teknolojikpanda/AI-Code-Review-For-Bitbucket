package com.teknolojikpanda.bitbucket.aireviewer.service;

import com.atlassian.activeobjects.external.ActiveObjects;
import com.atlassian.plugin.spring.scanner.annotation.imports.ComponentImport;
import com.teknolojikpanda.bitbucket.aireviewer.ao.AIReviewChunk;
import com.teknolojikpanda.bitbucket.aireviewer.ao.AIReviewHistory;
import net.java.ao.Query;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;
import java.util.Objects;

@Named
@Singleton
public class ReviewHistoryCleanupService {

    private static final Logger log = LoggerFactory.getLogger(ReviewHistoryCleanupService.class);

    private final ActiveObjects ao;

    @Inject
    public ReviewHistoryCleanupService(@ComponentImport ActiveObjects ao) {
        this.ao = Objects.requireNonNull(ao, "activeObjects");
    }

    public CleanupResult cleanupOlderThanDays(int retentionDays, int batchSize) {
        final int days = Math.max(1, retentionDays);
        final int limit = Math.max(1, batchSize);
        return ao.executeInTransaction(() -> {
            long start = System.currentTimeMillis();
            long cutoff = start - days * 24L * 60L * 60L * 1000L;
            AIReviewHistory[] histories = ao.find(AIReviewHistory.class,
                    Query.select()
                            .where("REVIEW_START_TIME < ?", cutoff)
                            .order("REVIEW_START_TIME ASC")
                            .limit(limit));
            int deletedHistory = 0;
            int deletedChunks = 0;
            for (AIReviewHistory history : histories) {
                AIReviewChunk[] chunks = history.getChunks();
                if (chunks.length > 0) {
                    ao.delete(chunks);
                    deletedChunks += chunks.length;
                }
                ao.delete(history);
                deletedHistory++;
            }
            int remaining = ao.count(AIReviewHistory.class,
                    Query.select().where("REVIEW_START_TIME < ?", cutoff));
            if (deletedHistory > 0) {
                log.info("Deleted {} history rows and {} chunks older than {} days", deletedHistory, deletedChunks, days);
            }
            long durationMs = Math.max(0, System.currentTimeMillis() - start);
            double recordsPerSecond = durationMs > 0 ? (deletedHistory / (durationMs / 1000d)) : deletedHistory;
            return new CleanupResult(days, limit, deletedHistory, deletedChunks, remaining, cutoff, durationMs, recordsPerSecond);
        });
    }

    public static final class CleanupResult {
        private final int retentionDays;
        private final int batchSize;
        private final int deletedHistories;
        private final int deletedChunks;
        private final int remainingCandidates;
        private final long cutoffEpochMs;
        private final long elapsedMs;
        private final double throughputPerSecond;
        private final int batchesExecuted;

        public CleanupResult(int retentionDays,
                             int batchSize,
                             int deletedHistories,
                             int deletedChunks,
                             int remainingCandidates,
                             long cutoffEpochMs,
                             long elapsedMs,
                             double throughputPerSecond) {
            this(retentionDays,
                    batchSize,
                    deletedHistories,
                    deletedChunks,
                    remainingCandidates,
                    cutoffEpochMs,
                    elapsedMs,
                    throughputPerSecond,
                    1);
        }

        public CleanupResult(int retentionDays,
                             int batchSize,
                             int deletedHistories,
                             int deletedChunks,
                             int remainingCandidates,
                             long cutoffEpochMs,
                             long elapsedMs,
                             double throughputPerSecond,
                             int batchesExecuted) {
            this.retentionDays = retentionDays;
            this.batchSize = batchSize;
            this.deletedHistories = deletedHistories;
            this.deletedChunks = deletedChunks;
            this.remainingCandidates = remainingCandidates;
            this.cutoffEpochMs = cutoffEpochMs;
            this.elapsedMs = elapsedMs;
            this.throughputPerSecond = throughputPerSecond;
            this.batchesExecuted = Math.max(1, batchesExecuted);
        }

        public int getRetentionDays() {
            return retentionDays;
        }

        public int getBatchSize() {
            return batchSize;
        }

        public int getDeletedHistories() {
            return deletedHistories;
        }

        public int getDeletedChunks() {
            return deletedChunks;
        }

        public int getRemainingCandidates() {
            return remainingCandidates;
        }

        public long getCutoffEpochMs() {
            return cutoffEpochMs;
        }

        public long getElapsedMs() {
            return elapsedMs;
        }

        public double getThroughputPerSecond() {
            return throughputPerSecond;
        }

        public int getBatchesExecuted() {
            return batchesExecuted;
        }
    }
}
