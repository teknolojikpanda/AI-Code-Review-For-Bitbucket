package com.example.bitbucket.aireviewer.ao;

import net.java.ao.Entity;
import net.java.ao.schema.NotNull;
import net.java.ao.schema.StringLength;
import net.java.ao.schema.Table;

/**
 * Active Objects entity capturing per-chunk invocation metrics for an AI review run.
 */
@Table("AI_REVIEW_CHUNK")
public interface AIReviewChunk extends Entity {

    @NotNull
    AIReviewHistory getHistory();
    void setHistory(AIReviewHistory history);

    @NotNull
    @StringLength(191)
    String getChunkId();
    void setChunkId(String chunkId);

    @StringLength(64)
    String getRole();
    void setRole(String role);

    @StringLength(255)
    String getModel();
    void setModel(String model);

    @StringLength(255)
    String getEndpoint();
    void setEndpoint(String endpoint);

    int getSequence();
    void setSequence(int sequence);

    int getAttempts();
    void setAttempts(int attempts);

    int getRetries();
    void setRetries(int retries);

    long getDurationMs();
    void setDurationMs(long durationMs);

    boolean isSuccess();
    void setSuccess(boolean success);

    boolean isModelNotFound();
    void setModelNotFound(boolean modelNotFound);

    @StringLength(StringLength.UNLIMITED)
    String getLastError();
    void setLastError(String lastError);
}
