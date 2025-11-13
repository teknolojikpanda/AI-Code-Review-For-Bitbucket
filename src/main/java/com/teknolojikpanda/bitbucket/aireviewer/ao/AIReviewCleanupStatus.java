package com.teknolojikpanda.bitbucket.aireviewer.ao;

import net.java.ao.Entity;
import net.java.ao.Preload;
import net.java.ao.schema.Table;

@Preload
@Table("AI_REVIEW_CLEANUP")
public interface AIReviewCleanupStatus extends Entity {

    int getRetentionDays();
    void setRetentionDays(int days);

    int getBatchSize();
    void setBatchSize(int batch);

    int getIntervalMinutes();
    void setIntervalMinutes(int minutes);

    int getWindowStartHour();
    void setWindowStartHour(int hour);

    int getWindowDurationMinutes();
    void setWindowDurationMinutes(int minutes);

    int getMaxBatchesPerWindow();
    void setMaxBatchesPerWindow(int batches);

    boolean isEnabled();
    void setEnabled(boolean enabled);

    long getLastRun();
    void setLastRun(long timestamp);

    long getLastDurationMs();
    void setLastDurationMs(long durationMs);

    int getLastDeletedHistories();
    void setLastDeletedHistories(int count);

    int getLastDeletedChunks();
    void setLastDeletedChunks(int count);

    int getLastBatchesExecuted();
    void setLastBatchesExecuted(int batches);

    String getLastError();
    void setLastError(String error);
}
