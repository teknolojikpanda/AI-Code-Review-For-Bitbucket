package com.teknolojikpanda.bitbucket.aireviewer.ao;

import net.java.ao.Entity;
import net.java.ao.Preload;
import net.java.ao.schema.Table;

@Preload
@Table("AI_REVIEW_CLN_AUDIT")
public interface AIReviewCleanupAudit extends Entity {

    long getRunTimestamp();
    void setRunTimestamp(long timestamp);

    long getDurationMs();
    void setDurationMs(long durationMs);

    int getDeletedHistories();
    void setDeletedHistories(int count);

    int getDeletedChunks();
    void setDeletedChunks(int count);

    String getActorUserKey();
    void setActorUserKey(String userKey);

    String getActorDisplayName();
    void setActorDisplayName(String displayName);

    boolean isManual();
    void setManual(boolean manual);

    boolean isSuccess();
    void setSuccess(boolean success);

    String getErrorMessage();
    void setErrorMessage(String error);
}
