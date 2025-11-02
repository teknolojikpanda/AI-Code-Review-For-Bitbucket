package com.example.bitbucket.aireviewer.ao;

import net.java.ao.Entity;
import net.java.ao.Preload;
import net.java.ao.schema.NotNull;
import net.java.ao.schema.StringLength;
import net.java.ao.schema.Table;

@Preload
@Table("AI_REVIEW_REPO_CFG")
public interface AIReviewRepoConfiguration extends Entity {

    @NotNull
    @StringLength(255)
    String getProjectKey();
    void setProjectKey(String projectKey);

    @NotNull
    @StringLength(255)
    String getRepositorySlug();
    void setRepositorySlug(String repositorySlug);

    @StringLength(StringLength.UNLIMITED)
    String getConfigurationJson();
    void setConfigurationJson(String configurationJson);

    boolean isInheritGlobal();
    void setInheritGlobal(boolean inheritGlobal);

    long getCreatedDate();
    void setCreatedDate(long createdDate);

    long getModifiedDate();
    void setModifiedDate(long modifiedDate);

    @StringLength(255)
    String getModifiedBy();
    void setModifiedBy(String userKey);
}
