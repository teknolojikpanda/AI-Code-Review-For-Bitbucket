package com.teknolojikpanda.bitbucket.aireviewer.ao;

import net.java.ao.Entity;
import net.java.ao.Preload;
import net.java.ao.schema.Indexed;
import net.java.ao.schema.NotNull;
import net.java.ao.schema.StringLength;
import net.java.ao.schema.Table;
import net.java.ao.schema.Unique;

@Preload
@Table("AI_GRD_RATE_BUCKET")
public interface GuardrailsRateBucket extends Entity {

    @Unique
    @NotNull
    @StringLength(255)
    String getBucketKey();
    void setBucketKey(String bucketKey);

    @NotNull
    @Indexed
    @StringLength(32)
    String getScope();
    void setScope(String scope);

    @NotNull
    @StringLength(255)
    String getIdentifier();
    void setIdentifier(String identifier);

    @Indexed
    long getWindowStart();
    void setWindowStart(long windowStart);

    int getConsumed();
    void setConsumed(int consumed);

    int getLimitPerHour();
    void setLimitPerHour(int limitPerHour);

    @Indexed
    long getUpdatedAt();
    void setUpdatedAt(long updatedAt);
}
