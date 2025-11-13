package com.teknolojikpanda.bitbucket.aireviewer.ao;

import net.java.ao.Entity;
import net.java.ao.Preload;
import net.java.ao.schema.Default;
import net.java.ao.schema.NotNull;
import net.java.ao.schema.StringLength;
import net.java.ao.schema.Table;

@Preload
@Table("AI_ROLLOUT_COHORT")
public interface AIReviewRolloutCohort extends Entity {

    @NotNull
    @StringLength(50)
    String getCohortKey();
    void setCohortKey(String cohortKey);

    @StringLength(255)
    String getDisplayName();
    void setDisplayName(String displayName);

    @StringLength(StringLength.UNLIMITED)
    String getDescription();
    void setDescription(String description);

    @NotNull
    @StringLength(16)
    String getScopeMode();
    void setScopeMode(String scopeMode);

    @StringLength(128)
    String getProjectKey();
    void setProjectKey(String projectKey);

    @StringLength(128)
    String getRepositorySlug();
    void setRepositorySlug(String repositorySlug);

    @Default("0")
    int getRolloutPercent();
    void setRolloutPercent(int percent);

    @StringLength(128)
    String getDarkFeatureKey();
    void setDarkFeatureKey(String key);

    @Default("false")
    boolean isEnabled();
    void setEnabled(boolean enabled);

    long getCreatedAt();
    void setCreatedAt(long createdAt);

    long getUpdatedAt();
    void setUpdatedAt(long updatedAt);

    @StringLength(255)
    String getUpdatedBy();
    void setUpdatedBy(String updatedBy);
}
