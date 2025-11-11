package com.teknolojikpanda.bitbucket.aireviewer.ao;

import net.java.ao.Entity;
import net.java.ao.schema.Default;
import net.java.ao.schema.Indexed;
import net.java.ao.schema.NotNull;
import net.java.ao.schema.StringLength;
import net.java.ao.schema.Table;

@Table("AI_GRD_BURST_CREDIT")
public interface GuardrailsBurstCredit extends Entity {

    @NotNull
    @StringLength(32)
    String getScope();
    void setScope(String scope);

    @NotNull
    @StringLength(255)
    String getIdentifier();
    void setIdentifier(String identifier);

    int getTokensGranted();
    void setTokensGranted(int tokensGranted);

    int getTokensConsumed();
    void setTokensConsumed(int tokensConsumed);

    @Indexed
    long getExpiresAt();
    void setExpiresAt(long expiresAt);

    long getCreatedAt();
    void setCreatedAt(long createdAt);

    String getCreatedBy();
    void setCreatedBy(String createdBy);

    String getCreatedByDisplayName();
    void setCreatedByDisplayName(String createdByDisplayName);

    @StringLength(StringLength.UNLIMITED)
    String getReason();
    void setReason(String reason);

    @StringLength(StringLength.UNLIMITED)
    String getNote();
    void setNote(String note);

    @Default("true")
    boolean isActive();
    void setActive(boolean active);

    long getLastConsumedAt();
    void setLastConsumedAt(long lastConsumedAt);
}
