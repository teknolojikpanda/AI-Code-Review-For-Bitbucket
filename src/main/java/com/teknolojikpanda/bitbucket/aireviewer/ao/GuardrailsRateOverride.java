package com.teknolojikpanda.bitbucket.aireviewer.ao;

import net.java.ao.Entity;
import net.java.ao.Preload;
import net.java.ao.schema.Indexed;
import net.java.ao.schema.NotNull;
import net.java.ao.schema.StringLength;
import net.java.ao.schema.Table;
import net.java.ao.schema.Unique;

@Preload
@Table("AI_GRD_RATE_OVERRIDE")
public interface GuardrailsRateOverride extends Entity {

    @Unique
    @NotNull
    @StringLength(255)
    String getScopeKey();
    void setScopeKey(String scopeKey);

    @NotNull
    @StringLength(32)
    String getScope();
    void setScope(String scope);

    @StringLength(255)
    String getIdentifier();
    void setIdentifier(String identifier);

    int getLimitPerHour();
    void setLimitPerHour(int limitPerHour);

    @Indexed
    long getCreatedAt();
    void setCreatedAt(long createdAt);

    @Indexed
    long getExpiresAt();
    void setExpiresAt(long expiresAt);

    @StringLength(255)
    String getCreatedBy();
    void setCreatedBy(String createdBy);

    @StringLength(255)
    String getCreatedByDisplayName();
    void setCreatedByDisplayName(String createdByDisplayName);

    @StringLength(StringLength.UNLIMITED)
    String getReason();
    void setReason(String reason);
}
