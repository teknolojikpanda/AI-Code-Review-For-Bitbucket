package com.example.bitbucket.aireviewer.ao;

import net.java.ao.Entity;
import net.java.ao.Preload;
import net.java.ao.schema.NotNull;
import net.java.ao.schema.StringLength;
import net.java.ao.schema.Table;

/**
 * Active Objects entity for storing AI Reviewer configuration
 */
@Preload
@Table("AI_REVIEW_CONFIG")
public interface AIReviewConfiguration extends Entity {

    // Ollama Configuration
    @NotNull
    @StringLength(StringLength.UNLIMITED)
    String getOllamaUrl();
    void setOllamaUrl(String url);

    @NotNull
    @StringLength(255)
    String getOllamaModel();
    void setOllamaModel(String model);

    @StringLength(255)
    String getFallbackModel();
    void setFallbackModel(String model);

    // Chunking Configuration
    int getMaxCharsPerChunk();
    void setMaxCharsPerChunk(int maxChars);

    int getMaxFilesPerChunk();
    void setMaxFilesPerChunk(int maxFiles);

    int getMaxChunks();
    void setMaxChunks(int maxChunks);

    // Timeout Configuration
    int getConnectTimeout();
    void setConnectTimeout(int timeout);

    int getReadTimeout();
    void setReadTimeout(int timeout);

    int getOllamaTimeout();
    void setOllamaTimeout(int timeout);

    // Review Configuration
    int getMaxIssuesPerFile();
    void setMaxIssuesPerFile(int maxIssues);

    int getMaxIssueComments();
    void setMaxIssueComments(int maxComments);

    int getParallelChunkThreads();
    void setParallelChunkThreads(int threads);

    int getMaxDiffSize();
    void setMaxDiffSize(int maxSize);

    // Retry Configuration
    int getMaxRetries();
    void setMaxRetries(int maxRetries);

    int getBaseRetryDelayMs();
    void setBaseRetryDelayMs(int delay);

    int getApiDelayMs();
    void setApiDelayMs(int delay);

    // Review Profile Configuration
    @StringLength(50)
    String getMinSeverity();
    void setMinSeverity(String severity);

    @StringLength(StringLength.UNLIMITED)
    String getRequireApprovalFor();
    void setRequireApprovalFor(String severities);

    boolean isSkipGeneratedFiles();
    void setSkipGeneratedFiles(boolean skip);

    boolean isSkipTests();
    void setSkipTests(boolean skip);

    // File Filtering Configuration
    @StringLength(StringLength.UNLIMITED)
    String getReviewExtensions();
    void setReviewExtensions(String extensions);

    @StringLength(StringLength.UNLIMITED)
    String getIgnorePatterns();
    void setIgnorePatterns(String patterns);

    @StringLength(StringLength.UNLIMITED)
    String getIgnorePaths();
    void setIgnorePaths(String paths);

    // Feature Flags
    boolean isEnabled();
    void setEnabled(boolean enabled);

    boolean isReviewDraftPRs();
    void setReviewDraftPRs(boolean review);

    // Configuration Metadata
    @StringLength(100)
    String getConfigurationName();
    void setConfigurationName(String name);

    @StringLength(StringLength.UNLIMITED)
    String getDescription();
    void setDescription(String description);

    boolean isGlobalDefault();
    void setGlobalDefault(boolean isDefault);

    long getCreatedDate();
    void setCreatedDate(long timestamp);

    long getModifiedDate();
    void setModifiedDate(long timestamp);

    @StringLength(255)
    String getModifiedBy();
    void setModifiedBy(String username);
}
