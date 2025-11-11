package com.teknolojikpanda.bitbucket.aireviewer.ao;

import net.java.ao.Entity;
import net.java.ao.Preload;
import net.java.ao.schema.Table;

@Preload
@Table("AI_GR_ALERT_CH")
public interface GuardrailsAlertChannel extends Entity {

    String getUrl();
    void setUrl(String url);

    String getDescription();
    void setDescription(String description);

    boolean isEnabled();
    void setEnabled(boolean enabled);

    long getCreatedAt();
    void setCreatedAt(long createdAt);

    long getUpdatedAt();
    void setUpdatedAt(long updatedAt);

    String getSecret();
    void setSecret(String secret);

    boolean isSignRequests();
    void setSignRequests(boolean signRequests);

    int getMaxRetries();
    void setMaxRetries(int retries);

    int getRetryBackoffSeconds();
    void setRetryBackoffSeconds(int seconds);
}
