package com.teknolojikpanda.bitbucket.aireviewer.ao;

import net.java.ao.Entity;
import net.java.ao.Preload;
import net.java.ao.schema.Indexed;
import net.java.ao.schema.NotNull;
import net.java.ao.schema.StringLength;
import net.java.ao.schema.Table;

@Preload
@Table("AI_GRD_RATE_INCIDENT")
public interface GuardrailsRateIncident extends Entity {

    @NotNull
    @StringLength(32)
    String getScope();
    void setScope(String scope);

    @StringLength(255)
    String getIdentifier();
    void setIdentifier(String identifier);

    @StringLength(255)
    String getProjectKey();
    void setProjectKey(String projectKey);

    @StringLength(255)
    String getRepositorySlug();
    void setRepositorySlug(String repositorySlug);

    @Indexed
    long getOccurredAt();
    void setOccurredAt(long occurredAt);

    int getLimitPerHour();
    void setLimitPerHour(int limitPerHour);

    long getRetryAfterMs();
    void setRetryAfterMs(long retryAfterMs);

    @StringLength(StringLength.UNLIMITED)
    String getReason();
    void setReason(String reason);
}
