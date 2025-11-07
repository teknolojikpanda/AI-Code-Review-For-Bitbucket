package com.teknolojikpanda.bitbucket.aireviewer.ao;

import net.java.ao.Entity;
import net.java.ao.Preload;
import net.java.ao.schema.NotNull;
import net.java.ao.schema.StringLength;
import net.java.ao.schema.Table;

@Preload
@Table("AI_REVIEW_SCHED_STATE")
public interface AIReviewSchedulerState extends Entity {

    @NotNull
    @StringLength(32)
    String getState();
    void setState(String state);

    @StringLength(255)
    String getUpdatedBy();
    void setUpdatedBy(String userKey);

    @StringLength(255)
    String getUpdatedByDisplayName();
    void setUpdatedByDisplayName(String displayName);

    long getUpdatedAt();
    void setUpdatedAt(long timestamp);

    @StringLength(StringLength.UNLIMITED)
    String getReason();
    void setReason(String reason);
}
