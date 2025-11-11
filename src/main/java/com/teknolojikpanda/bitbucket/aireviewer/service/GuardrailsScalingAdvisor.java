package com.teknolojikpanda.bitbucket.aireviewer.service;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Produces human-readable scaling hints when queue and worker metrics indicate sustained pressure.
 */
@Named
@Singleton
public class GuardrailsScalingAdvisor {

    private static final double HIGH_WORKER_UTILIZATION = 0.90d;
    private static final double EXTREME_WORKER_UTILIZATION = 0.97d;
    private static final double ACTIVE_RATIO_THRESHOLD = 0.95d;

    @Inject
    public GuardrailsScalingAdvisor() {
    }

    public List<ScalingHint> evaluate(ReviewConcurrencyController.QueueStats queueStats,
                                      List<GuardrailsWorkerNodeService.WorkerNodeRecord> workerNodes) {
        boolean hasQueue = queueStats != null;
        List<ScalingHint> hints = new ArrayList<>();
        double avgUtilization = computeAverageUtilization(workerNodes);
        int saturatedNodes = countSaturatedNodes(workerNodes, HIGH_WORKER_UTILIZATION);

        if (!workerNodes.isEmpty() &&
                (avgUtilization >= HIGH_WORKER_UTILIZATION || saturatedNodes >= Math.max(1, workerNodes.size() / 2))) {
            double displayUtil = Math.min(1.0d, Math.max(0d, avgUtilization));
            hints.add(ScalingHint.warning(
                    "Worker nodes are saturated",
                    String.format(Locale.ENGLISH,
                            "Average utilization is %.0f%% across %d node(s); %d node(s) are above %.0f%% active threads.",
                            displayUtil * 100.0d,
                            workerNodes.size(),
                            saturatedNodes,
                            HIGH_WORKER_UTILIZATION * 100.0d),
                    "Add worker nodes or enable auto-scaling so AI reviews keep up with incoming load."));
        }

        if (avgUtilization >= EXTREME_WORKER_UTILIZATION) {
            hints.add(ScalingHint.critical(
                    "Worker pool is critically overcommitted",
                    String.format(Locale.ENGLISH,
                            "Worker threads average %.0f%% utilization and queued tasks remain high.",
                            avgUtilization * 100.0d),
                    "Scale out immediately (add nodes or raise the worker pool) to avoid review starvation."));
        }

        if (hasQueue) {
            int waiting = queueStats.getWaiting();
            int maxConcurrent = Math.max(1, queueStats.getMaxConcurrent());
            if (waiting >= Math.max(5, maxConcurrent)) {
                hints.add(ScalingHint.warning(
                        "Review queue backlog keeps growing",
                        String.format(Locale.ENGLISH,
                                "%d review(s) are waiting while only %d slot(s) run in parallel.",
                                waiting,
                                maxConcurrent),
                        "Consider adding worker capacity or increasing max concurrent reviews for critical repositories."));
            }

            double activeRatio = maxConcurrent > 0 ? queueStats.getActive() / (double) maxConcurrent : 0d;
            if (activeRatio >= ACTIVE_RATIO_THRESHOLD && waiting > 0) {
                hints.add(ScalingHint.info(
                        "Scheduler is pegged at its concurrent limit",
                        String.format(Locale.ENGLISH,
                                "%d/%d slots are in use and the queue still has %d entries.",
                                queueStats.getActive(),
                                maxConcurrent,
                                waiting),
                        "Review whether `maxConcurrentReviews` is too conservative for this cluster size."));
            }
        }

        return hints;
    }

    private double computeAverageUtilization(List<GuardrailsWorkerNodeService.WorkerNodeRecord> workerNodes) {
        if (workerNodes == null || workerNodes.isEmpty()) {
            return 0d;
        }
        double total = 0d;
        for (GuardrailsWorkerNodeService.WorkerNodeRecord node : workerNodes) {
            int configured = Math.max(1, node.getConfiguredSize());
            total += Math.min(1.0d, Math.max(0d, node.getActiveThreads() / (double) configured));
        }
        return total / workerNodes.size();
    }

    private int countSaturatedNodes(List<GuardrailsWorkerNodeService.WorkerNodeRecord> workerNodes,
                                    double threshold) {
        if (workerNodes == null || workerNodes.isEmpty()) {
            return 0;
        }
        int count = 0;
        for (GuardrailsWorkerNodeService.WorkerNodeRecord node : workerNodes) {
            int configured = Math.max(1, node.getConfiguredSize());
            double util = node.getActiveThreads() / (double) configured;
            if (util >= threshold) {
                count++;
            }
        }
        return count;
    }

    public static final class ScalingHint {
        private final String severity;
        private final String summary;
        private final String detail;
        private final String recommendation;

        private ScalingHint(String severity, String summary, String detail, String recommendation) {
            this.severity = severity;
            this.summary = summary;
            this.detail = detail;
            this.recommendation = recommendation;
        }

        public static ScalingHint info(String summary, String detail, String recommendation) {
            return new ScalingHint("info", summary, detail, recommendation);
        }

        public static ScalingHint warning(String summary, String detail, String recommendation) {
            return new ScalingHint("warning", summary, detail, recommendation);
        }

        public static ScalingHint critical(String summary, String detail, String recommendation) {
            return new ScalingHint("critical", summary, detail, recommendation);
        }

        public String getSeverity() {
            return severity;
        }

        public String getSummary() {
            return summary;
        }

        public String getDetail() {
            return detail;
        }

        public String getRecommendation() {
            return recommendation;
        }

        public java.util.Map<String, Object> toMap() {
            java.util.Map<String, Object> map = new java.util.LinkedHashMap<>();
            map.put("severity", severity);
            map.put("summary", summary);
            map.put("detail", detail);
            map.put("recommendation", recommendation);
            return Collections.unmodifiableMap(map);
        }
    }
}
