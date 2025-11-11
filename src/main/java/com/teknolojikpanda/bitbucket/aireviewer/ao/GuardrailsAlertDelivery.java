package com.teknolojikpanda.bitbucket.aireviewer.ao;

import net.java.ao.Entity;
import net.java.ao.schema.StringLength;
import net.java.ao.schema.Table;

@Table("AI_GUARDRAILS_ALERT_DELIVERY")
public interface GuardrailsAlertDelivery extends Entity {

    int getChannelId();
    void setChannelId(int channelId);

    String getChannelUrl();
    void setChannelUrl(String url);

    String getChannelDescription();
    void setChannelDescription(String description);

    long getDeliveredAt();
    void setDeliveredAt(long deliveredAt);

    boolean isSuccess();
    void setSuccess(boolean success);

    boolean isTest();
    void setTest(boolean test);

    int getHttpStatus();
    void setHttpStatus(int status);

    @StringLength(StringLength.UNLIMITED)
    String getPayload();
    void setPayload(String payload);

    @StringLength(StringLength.UNLIMITED)
    String getErrorMessage();
    void setErrorMessage(String errorMessage);

    boolean isAcknowledged();
    void setAcknowledged(boolean acknowledged);

    String getAckUserKey();
    void setAckUserKey(String userKey);

    String getAckUserDisplayName();
    void setAckUserDisplayName(String displayName);

    long getAckTimestamp();
    void setAckTimestamp(long ackTimestamp);

    @StringLength(StringLength.UNLIMITED)
    String getAckNote();
    void setAckNote(String note);
}
