package com.teknolojikpanda.bitbucket.aireviewer.ao;

import net.java.ao.Entity;
import net.java.ao.Preload;
import net.java.ao.schema.NotNull;
import net.java.ao.schema.StringLength;
import net.java.ao.schema.Table;

@Preload
@Table("AI_QUEUE_AUD")
public interface AIReviewQueueAudit extends Entity {

    @NotNull
    long getCreatedAt();
    void setCreatedAt(long createdAt);

    @NotNull
    @StringLength(32)
    String getAction();
    void setAction(String action);

    @StringLength(255)
    String getRunId();
    void setRunId(String runId);

    @StringLength(64)
    String getProjectKey();
    void setProjectKey(String projectKey);

    @StringLength(128)
    String getRepositorySlug();
    void setRepositorySlug(String repositorySlug);

    long getPullRequestId();
    void setPullRequestId(long pullRequestId);

    boolean isManual();
    void setManual(boolean manual);

    boolean isUpdate();
    void setUpdate(boolean update);

    boolean isForce();
    void setForce(boolean force);

    @StringLength(255)
    String getActor();
    void setActor(String actor);

    @StringLength(StringLength.UNLIMITED)
    String getNote();
    void setNote(String note);

    @StringLength(255)
    String getRequestedBy();
    void setRequestedBy(String requestedBy);
}
