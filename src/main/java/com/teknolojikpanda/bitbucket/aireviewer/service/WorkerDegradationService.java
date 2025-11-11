package com.teknolojikpanda.bitbucket.aireviewer.service;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Applies graceful degradation rules to the review configuration when the worker pool is saturated.
 */
@Named
@Singleton
public class WorkerDegradationService {

    private static final double MODERATE_THRESHOLD = 0.90d;
    private static final double AGGRESSIVE_THRESHOLD = 0.95d;
    private static final double CRITICAL_THRESHOLD = 0.98d;

    private final ReviewWorkerPool workerPool;

    @Inject
    public WorkerDegradationService(ReviewWorkerPool workerPool) {
        this.workerPool = Objects.requireNonNull(workerPool, "workerPool");
    }

    /**
     * Applies degradation rules to the provided configuration map and returns the effective result.
     */
    public Result apply(Map<String, Object> configuration) {
        if (configuration == null || configuration.isEmpty()) {
            return Result.passThrough(Collections.emptyMap());
        }
        boolean enabled = toBoolean(configuration.get("workerDegradationEnabled"), true);
        if (!enabled) {
            return Result.passThrough(configuration);
        }

        ReviewWorkerPool.WorkerPoolSnapshot snapshot = workerPool.snapshot();
        if (snapshot == null || snapshot.getConfiguredSize() <= 0) {
            return Result.passThrough(configuration);
        }

        int configuredSize = Math.max(1, snapshot.getConfiguredSize());
        double utilization = snapshot.getActiveThreads() / (double) configuredSize;
        int queuedTasks = Math.max(0, snapshot.getQueuedTasks());

        DegradationLevel level = determineLevel(utilization, queuedTasks, configuredSize);
        int originalParallel = toInt(configuration.get("parallelThreads"), 4);

        if (level == DegradationLevel.NONE || originalParallel <= 1) {
            return Result.passThrough(configuration, utilization, queuedTasks);
        }

        int adjustedParallel = Math.max(1, level.apply(originalParallel));
        if (adjustedParallel >= originalParallel) {
            return Result.passThrough(configuration, utilization, queuedTasks);
        }

        Map<String, Object> mutated = new LinkedHashMap<>(configuration);
        mutated.put("parallelThreads", adjustedParallel);
        mutated.put("workerDegradationEnabled", true);
        mutated.put("workerDegradationActive", true);
        mutated.put("workerDegradationLevel", level.name());

        return new Result(mutated, true, originalParallel, adjustedParallel, utilization, queuedTasks, level);
    }

    private DegradationLevel determineLevel(double utilization, int queuedTasks, int configuredSize) {
        if (utilization >= CRITICAL_THRESHOLD || queuedTasks >= configuredSize * 3) {
            return DegradationLevel.CRITICAL;
        }
        if (utilization >= AGGRESSIVE_THRESHOLD || queuedTasks >= configuredSize * 2) {
            return DegradationLevel.AGGRESSIVE;
        }
        if (utilization >= MODERATE_THRESHOLD || queuedTasks >= configuredSize) {
            return DegradationLevel.MODERATE;
        }
        return DegradationLevel.NONE;
    }

    private boolean toBoolean(Object value, boolean defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        if (value instanceof String) {
            return Boolean.parseBoolean(((String) value).trim());
        }
        return defaultValue;
    }

    private int toInt(Object value, int defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        if (value instanceof String) {
            try {
                return Integer.parseInt(((String) value).trim());
            } catch (NumberFormatException ignored) {
                return defaultValue;
            }
        }
        return defaultValue;
    }

    public enum DegradationLevel {
        NONE {
            @Override
            int apply(int baseParallel) {
                return baseParallel;
            }
        },
        MODERATE {
            @Override
            int apply(int baseParallel) {
                return Math.max(1, baseParallel - 1);
            }
        },
        AGGRESSIVE {
            @Override
            int apply(int baseParallel) {
                return Math.max(1, (int) Math.ceil(baseParallel * 0.5d));
            }
        },
        CRITICAL {
            @Override
            int apply(int baseParallel) {
                return 1;
            }
        };

        abstract int apply(int baseParallel);
    }

    public static final class Result {
        private final Map<String, Object> configuration;
        private final boolean degraded;
        private final int originalParallelThreads;
        private final int adjustedParallelThreads;
        private final double workerUtilization;
        private final int queuedTasks;
        private final DegradationLevel level;

        private Result(Map<String, Object> configuration,
                       boolean degraded,
                       int originalParallelThreads,
                       int adjustedParallelThreads,
                       double workerUtilization,
                       int queuedTasks,
                       DegradationLevel level) {
            this.configuration = configuration;
            this.degraded = degraded;
            this.originalParallelThreads = originalParallelThreads;
            this.adjustedParallelThreads = adjustedParallelThreads;
            this.workerUtilization = workerUtilization;
            this.queuedTasks = queuedTasks;
            this.level = level != null ? level : DegradationLevel.NONE;
        }

        public static Result passThrough(Map<String, Object> configuration) {
            return new Result(configuration, false, -1, -1, Double.NaN, 0, DegradationLevel.NONE);
        }

        public static Result passThrough(Map<String, Object> configuration,
                                          double utilization,
                                          int queuedTasks) {
            return new Result(configuration, false, -1, -1, utilization, queuedTasks, DegradationLevel.NONE);
        }

        public Map<String, Object> getConfiguration() {
            return configuration;
        }

        public boolean isDegraded() {
            return degraded;
        }

        public int getOriginalParallelThreads() {
            return originalParallelThreads;
        }

        public int getAdjustedParallelThreads() {
            return adjustedParallelThreads;
        }

        public double getWorkerUtilization() {
            return workerUtilization;
        }

        public int getQueuedTasks() {
            return queuedTasks;
        }

        public DegradationLevel getLevel() {
            return level;
        }
    }
}
