package com.teknolojikpanda.bitbucket.aireviewer.service;

import com.atlassian.activeobjects.external.ActiveObjects;
import com.atlassian.bitbucket.server.ApplicationPropertiesService;
import com.atlassian.plugin.spring.scanner.annotation.imports.ComponentImport;
import com.teknolojikpanda.bitbucket.aireviewer.ao.GuardrailsWorkerNodeState;
import net.java.ao.DBParam;
import net.java.ao.Query;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * Persists worker pool snapshots per Bitbucket node so guardrails telemetry can render
 * utilization across the cluster.
 */
@Named
@Singleton
public class GuardrailsWorkerNodeService {

    private static final Logger log = LoggerFactory.getLogger(GuardrailsWorkerNodeService.class);
    private static final long DEFAULT_STALE_WINDOW_MS = TimeUnit.MINUTES.toMillis(2);

    private final ActiveObjects ao;
    private final ApplicationPropertiesService applicationPropertiesService;

    @Inject
    public GuardrailsWorkerNodeService(@ComponentImport ActiveObjects ao,
                                       @ComponentImport ApplicationPropertiesService applicationPropertiesService) {
        this.ao = Objects.requireNonNull(ao, "activeObjects");
        this.applicationPropertiesService = Objects.requireNonNull(applicationPropertiesService, "applicationPropertiesService");
    }

    /**
     * Records the latest worker pool snapshot for the current node.
     */
    public WorkerNodeRecord recordLocalSnapshot(ReviewWorkerPool.WorkerPoolSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        try {
            return ao.executeInTransaction(() -> upsertSnapshot(snapshot));
        } catch (IllegalStateException ex) {
            log.debug("ActiveObjects not ready for worker node snapshot yet: {}", ex.getMessage());
            return WorkerNodeRecord.forLocalOnly(resolveNodeId(), resolveNodeName(), snapshot, System.currentTimeMillis(), true);
        }
    }

    /**
     * Lists the most recent worker snapshots for every node, flagging entries older than the
     * default stale window.
     */
    public List<WorkerNodeRecord> listSnapshots() {
        return listSnapshots(DEFAULT_STALE_WINDOW_MS);
    }

    public List<WorkerNodeRecord> listSnapshots(long staleWindowMs) {
        long cutoff = staleWindowMs > 0 ? System.currentTimeMillis() - staleWindowMs : 0L;
        GuardrailsWorkerNodeState[] rows;
        try {
            rows = ao.executeInTransaction(() ->
                    ao.find(GuardrailsWorkerNodeState.class, Query.select().order("CAPTURED_AT DESC")));
        } catch (IllegalStateException ex) {
            log.debug("ActiveObjects not ready when listing worker node snapshots: {}", ex.getMessage());
            return Collections.emptyList();
        }
        if (rows.length == 0) {
            return Collections.emptyList();
        }
        List<WorkerNodeRecord> records = new ArrayList<>(rows.length);
        for (GuardrailsWorkerNodeState row : rows) {
            boolean stale = cutoff > 0 && row.getCapturedAt() < cutoff;
            records.add(toRecord(row, stale));
        }
        return records;
    }

    private WorkerNodeRecord upsertSnapshot(ReviewWorkerPool.WorkerPoolSnapshot snapshot) {
        long now = System.currentTimeMillis();
        String nodeId = resolveNodeId();
        GuardrailsWorkerNodeState row = findOrCreate(nodeId);
        row.setNodeId(nodeId);
        row.setNodeName(resolveNodeName());
        row.setCapturedAt(now);
        row.setConfiguredSize(snapshot.getConfiguredSize());
        row.setActiveThreads(snapshot.getActiveThreads());
        row.setQueuedTasks(snapshot.getQueuedTasks());
        row.setCurrentPoolSize(snapshot.getCurrentPoolSize());
        row.setLargestPoolSize(snapshot.getLargestPoolSize());
        row.setTotalTasks(snapshot.getTotalTasks());
        row.setCompletedTasks(snapshot.getCompletedTasks());
        row.save();
        return toRecord(row, false);
    }

    private GuardrailsWorkerNodeState findOrCreate(String nodeId) {
        GuardrailsWorkerNodeState[] existing = ao.find(GuardrailsWorkerNodeState.class, Query.select().where("NODE_ID = ?", nodeId));
        if (existing.length > 0) {
            return existing[0];
        }
        return ao.create(GuardrailsWorkerNodeState.class, new DBParam("NODE_ID", nodeId));
    }

    private WorkerNodeRecord toRecord(GuardrailsWorkerNodeState row, boolean stale) {
        return new WorkerNodeRecord(
                row.getNodeId(),
                row.getNodeName(),
                row.getConfiguredSize(),
                row.getActiveThreads(),
                row.getQueuedTasks(),
                row.getCurrentPoolSize(),
                row.getLargestPoolSize(),
                row.getTotalTasks(),
                row.getCompletedTasks(),
                row.getCapturedAt(),
                stale);
    }

    private String resolveNodeId() {
        try {
            String serverId = applicationPropertiesService.getServerId();
            if (serverId != null && !serverId.trim().isEmpty()) {
                return serverId.trim();
            }
        } catch (Exception ex) {
            log.debug("Unable to resolve serverId: {}", ex.getMessage());
        }
        return "unknown-node";
    }

    private String resolveNodeName() {
        String displayName = null;
        try {
            displayName = applicationPropertiesService.getDisplayName();
            if (displayName == null || displayName.trim().isEmpty()) {
                displayName = hostFromBaseUrl(applicationPropertiesService.getBaseUrl());
            }
        } catch (Exception ex) {
            log.debug("Unable to resolve node display name: {}", ex.getMessage());
        }
        return displayName != null ? displayName : resolveNodeId();
    }

    private String hostFromBaseUrl(URI baseUrl) {
        if (baseUrl == null || baseUrl.getHost() == null || baseUrl.getHost().isEmpty()) {
            return null;
        }
        return baseUrl.getHost().toLowerCase(Locale.ROOT);
    }

    /**
     * Immutable value describing a worker node snapshot.
     */
    public static final class WorkerNodeRecord {
        private final String nodeId;
        private final String nodeName;
        private final int configuredSize;
        private final int activeThreads;
        private final int queuedTasks;
        private final int currentPoolSize;
        private final int largestPoolSize;
        private final long totalTasks;
        private final long completedTasks;
        private final long capturedAt;
        private final boolean stale;

        WorkerNodeRecord(String nodeId,
                         String nodeName,
                         int configuredSize,
                         int activeThreads,
                         int queuedTasks,
                         int currentPoolSize,
                         int largestPoolSize,
                         long totalTasks,
                         long completedTasks,
                         long capturedAt,
                         boolean stale) {
            this.nodeId = nodeId;
            this.nodeName = nodeName;
            this.configuredSize = configuredSize;
            this.activeThreads = activeThreads;
            this.queuedTasks = queuedTasks;
            this.currentPoolSize = currentPoolSize;
            this.largestPoolSize = largestPoolSize;
            this.totalTasks = totalTasks;
            this.completedTasks = completedTasks;
            this.capturedAt = capturedAt;
            this.stale = stale;
        }

        static WorkerNodeRecord forLocalOnly(String nodeId,
                                             String nodeName,
                                             ReviewWorkerPool.WorkerPoolSnapshot snapshot,
                                             long capturedAt,
                                             boolean stale) {
            return new WorkerNodeRecord(
                    nodeId,
                    nodeName,
                    snapshot.getConfiguredSize(),
                    snapshot.getActiveThreads(),
                    snapshot.getQueuedTasks(),
                    snapshot.getCurrentPoolSize(),
                    snapshot.getLargestPoolSize(),
                    snapshot.getTotalTasks(),
                    snapshot.getCompletedTasks(),
                    capturedAt,
                    stale);
        }

        public String getNodeId() {
            return nodeId;
        }

        public String getNodeName() {
            return nodeName;
        }

        public int getConfiguredSize() {
            return configuredSize;
        }

        public int getActiveThreads() {
            return activeThreads;
        }

        public int getQueuedTasks() {
            return queuedTasks;
        }

        public int getCurrentPoolSize() {
            return currentPoolSize;
        }

        public int getLargestPoolSize() {
            return largestPoolSize;
        }

        public long getTotalTasks() {
            return totalTasks;
        }

        public long getCompletedTasks() {
            return completedTasks;
        }

        public long getCapturedAt() {
            return capturedAt;
        }

        public boolean isStale() {
            return stale;
        }

        public double getUtilization() {
            int configured = Math.max(1, configuredSize);
            return Math.min(1.0d, Math.max(0d, activeThreads / (double) configured));
        }

        public long getQueuedTasksPerWorker() {
            int configured = Math.max(1, configuredSize);
            return Math.max(0, queuedTasks) / configured;
        }
    }
}
