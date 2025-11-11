package com.teknolojikpanda.bitbucket.aireviewer.ao;

import net.java.ao.Entity;
import net.java.ao.Preload;
import net.java.ao.schema.NotNull;
import net.java.ao.schema.StringLength;
import net.java.ao.schema.Table;

/**
 * Stores the last known worker pool snapshot for each Bitbucket node so that telemetry can
 * display per-node utilization even in clustered deployments.
 */
@Preload
@Table("AI_WORKER_NODE")
public interface GuardrailsWorkerNodeState extends Entity {

    @NotNull
    @StringLength(255)
    String getNodeId();
    void setNodeId(String nodeId);

    @StringLength(255)
    String getNodeName();
    void setNodeName(String nodeName);

    long getCapturedAt();
    void setCapturedAt(long capturedAt);

    int getConfiguredSize();
    void setConfiguredSize(int configuredSize);

    int getActiveThreads();
    void setActiveThreads(int activeThreads);

    int getQueuedTasks();
    void setQueuedTasks(int queuedTasks);

    int getCurrentPoolSize();
    void setCurrentPoolSize(int currentPoolSize);

    int getLargestPoolSize();
    void setLargestPoolSize(int largestPoolSize);

    long getTotalTasks();
    void setTotalTasks(long totalTasks);

    long getCompletedTasks();
    void setCompletedTasks(long completedTasks);
}
