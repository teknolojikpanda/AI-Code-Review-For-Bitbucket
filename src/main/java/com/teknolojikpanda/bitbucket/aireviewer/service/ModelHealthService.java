package com.teknolojikpanda.bitbucket.aireviewer.service;

import javax.inject.Named;
import javax.inject.Singleton;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;

/**
 * Tracks model health based on proactive probes and exposes helper methods that can
 * adjust runtime configuration when the primary model is degraded.
 */
@Named
@Singleton
public class ModelHealthService {

    private static final int DEGRADED_FAILURE_THRESHOLD = 2;
    private static final int FAILED_FAILURE_THRESHOLD = 5;
    private static final long STALE_AFTER_MS = TimeUnit.MINUTES.toMillis(10);
    private static final String DEFAULT_ENDPOINT = "http://0.0.0.0:11434";

    private final ConcurrentMap<ModelKey, HealthState> states = new ConcurrentHashMap<>();

    /**
     * Records a successful probe and clears any degraded status for the model.
     */
    public void recordSuccess(String endpoint, String model, long latencyMs) {
        String normalizedEndpoint = normalizeEndpoint(endpoint);
        String normalizedModel = normalizeModel(model);
        if (normalizedModel.isEmpty()) {
            return;
        }
        states.compute(ModelKey.of(normalizedEndpoint, normalizedModel),
                (key, state) -> HealthState.success(latencyMs));
    }

    /**
     * Records a probe failure for the given model.
     */
    public void recordFailure(String endpoint, String model, String message) {
        String normalizedEndpoint = normalizeEndpoint(endpoint);
        String normalizedModel = normalizeModel(model);
        if (normalizedModel.isEmpty()) {
            return;
        }
        states.compute(ModelKey.of(normalizedEndpoint, normalizedModel),
                (key, state) -> HealthState.failure(state, message));
    }

    /**
     * Applies health overrides to the provided configuration map, adding a runtime flag that
     * instructs the review client to skip the primary model when probes show it is degraded.
     */
    public Result apply(Map<String, Object> configuration) {
        if (configuration == null || configuration.isEmpty()) {
            return Result.passThrough(Collections.emptyMap());
        }
        String endpoint = normalizeEndpoint(configuration.get("ollamaUrl"));
        String primaryModel = normalizeModel(configuration.get("ollamaModel"));
        String fallbackModel = normalizeModel(configuration.get("fallbackModel"));

        HealthSnapshot primarySnapshot = snapshot(endpoint, primaryModel);
        HealthSnapshot fallbackSnapshot = fallbackModel.isEmpty() ? null : snapshot(endpoint, fallbackModel);

        Map<String, Object> mutated = configuration;
        boolean failoverApplied = false;
        if (primarySnapshot.isDegraded() && fallbackSnapshot != null && fallbackSnapshot.isHealthy()) {
            mutated = new LinkedHashMap<>(configuration);
            mutated.put("skipPrimaryModel", true);
            mutated.put("primaryModelDegraded", true);
            failoverApplied = true;
        }
        return new Result(mutated, failoverApplied, primarySnapshot, fallbackSnapshot);
    }

    /**
     * Returns a snapshot of the tracked models for telemetry endpoints.
     */
    public Map<String, Object> snapshot() {
        List<Map<String, Object>> entries = new ArrayList<>();
        long now = System.currentTimeMillis();
        states.forEach((key, state) -> {
            HealthSnapshot snapshot = HealthSnapshot.fromState(
                    key.endpoint, key.model, state, now);
            entries.add(snapshot.toMap());
        });
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("generatedAt", now);
        payload.put("models", entries);
        return payload;
    }

    private HealthSnapshot snapshot(String endpoint, String model) {
        if (model.isEmpty()) {
            return HealthSnapshot.blank(endpoint, model);
        }
        ModelKey key = ModelKey.of(endpoint, model);
        HealthState state = states.get(key);
        return HealthSnapshot.fromState(endpoint, model, state, System.currentTimeMillis());
    }

    private String normalizeEndpoint(Object value) {
        String raw = value == null ? "" : value.toString().trim();
        if (raw.isEmpty()) {
            return DEFAULT_ENDPOINT;
        }
        return raw.replaceAll("/+$", "");
    }

    private String normalizeModel(Object value) {
        if (value == null) {
            return "";
        }
        return value.toString().trim();
    }

    private static final class ModelKey {
        private final String endpoint;
        private final String model;

        private ModelKey(String endpoint, String model) {
            this.endpoint = endpoint;
            this.model = model;
        }

        private static ModelKey of(String endpoint, String model) {
            return new ModelKey(endpoint, model);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof ModelKey)) {
                return false;
            }
            ModelKey modelKey = (ModelKey) o;
            return endpoint.equals(modelKey.endpoint) && model.equals(modelKey.model);
        }

        @Override
        public int hashCode() {
            return Objects.hash(endpoint, model);
        }
    }

    private static final class HealthState {
        private final HealthStatus status;
        private final long updatedAt;
        private final int consecutiveFailures;
        private final long lastLatencyMs;
        private final String message;

        private HealthState(HealthStatus status,
                            long updatedAt,
                            int consecutiveFailures,
                            long lastLatencyMs,
                            String message) {
            this.status = status;
            this.updatedAt = updatedAt;
            this.consecutiveFailures = consecutiveFailures;
            this.lastLatencyMs = lastLatencyMs;
            this.message = message;
        }

        private static HealthState success(long latencyMs) {
            return new HealthState(HealthStatus.HEALTHY,
                    System.currentTimeMillis(),
                    0,
                    latencyMs,
                    null);
        }

        private static HealthState failure(HealthState previous, String message) {
            long now = System.currentTimeMillis();
            int failures = previous != null ? previous.consecutiveFailures + 1 : 1;
            HealthStatus status;
            if (failures >= FAILED_FAILURE_THRESHOLD) {
                status = HealthStatus.FAILED;
            } else if (failures >= DEGRADED_FAILURE_THRESHOLD) {
                status = HealthStatus.DEGRADED;
            } else if (previous != null && previous.status == HealthStatus.DEGRADED) {
                status = HealthStatus.DEGRADED;
            } else {
                status = HealthStatus.HEALTHY;
            }
            return new HealthState(status, now, failures, previous != null ? previous.lastLatencyMs : 0, message);
        }
    }

    public enum HealthStatus {
        UNKNOWN,
        HEALTHY,
        DEGRADED,
        FAILED
    }

    public static final class HealthSnapshot {
        private final String endpoint;
        private final String model;
        private final HealthStatus status;
        private final long lastLatencyMs;
        private final long updatedAt;
        private final int consecutiveFailures;
        private final String message;

        private HealthSnapshot(String endpoint,
                               String model,
                               HealthStatus status,
                               long lastLatencyMs,
                               long updatedAt,
                               int consecutiveFailures,
                               String message) {
            this.endpoint = endpoint;
            this.model = model;
            this.status = status;
            this.lastLatencyMs = lastLatencyMs;
            this.updatedAt = updatedAt;
            this.consecutiveFailures = consecutiveFailures;
            this.message = message;
        }

        private static HealthSnapshot fromState(String endpoint,
                                                String model,
                                                HealthState state,
                                                long now) {
            if (state == null) {
                return new HealthSnapshot(endpoint, model, HealthStatus.UNKNOWN, 0, 0, 0, null);
            }
            HealthStatus effectiveStatus = state.status;
            if (now - state.updatedAt > STALE_AFTER_MS) {
                effectiveStatus = HealthStatus.UNKNOWN;
            }
            return new HealthSnapshot(endpoint,
                    model,
                    effectiveStatus,
                    state.lastLatencyMs,
                    state.updatedAt,
                    state.consecutiveFailures,
                    state.message);
        }

        private static HealthSnapshot blank(String endpoint, String model) {
            return new HealthSnapshot(endpoint, model, HealthStatus.UNKNOWN, 0, 0, 0, null);
        }

        public boolean isDegraded() {
            return status == HealthStatus.DEGRADED || status == HealthStatus.FAILED;
        }

        public boolean isHealthy() {
            return status == HealthStatus.HEALTHY || status == HealthStatus.UNKNOWN;
        }

        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("endpoint", endpoint);
            map.put("model", model);
            map.put("status", status.name().toLowerCase(Locale.ROOT));
            map.put("lastLatencyMs", lastLatencyMs);
            map.put("updatedAt", updatedAt);
            map.put("consecutiveFailures", consecutiveFailures);
            if (message != null && !message.isBlank()) {
                map.put("message", message);
            }
            return map;
        }

        public String getMessage() {
            return message;
        }

        public HealthStatus getStatus() {
            return status;
        }
    }

    public static final class Result {
        private final Map<String, Object> configuration;
        private final boolean failoverApplied;
        private final HealthSnapshot primarySnapshot;
        private final HealthSnapshot fallbackSnapshot;

        private Result(Map<String, Object> configuration,
                       boolean failoverApplied,
                       HealthSnapshot primarySnapshot,
                       HealthSnapshot fallbackSnapshot) {
            this.configuration = configuration;
            this.failoverApplied = failoverApplied;
            this.primarySnapshot = primarySnapshot;
            this.fallbackSnapshot = fallbackSnapshot;
        }

        public static Result passThrough(Map<String, Object> configuration) {
            return new Result(configuration, false, null, null);
        }

        public Map<String, Object> getConfiguration() {
            return configuration;
        }

        public boolean isFailoverApplied() {
            return failoverApplied;
        }

        public boolean isPrimaryDegraded() {
            return primarySnapshot != null && primarySnapshot.isDegraded();
        }

        public HealthSnapshot getPrimarySnapshot() {
            return primarySnapshot;
        }

        public HealthSnapshot getFallbackSnapshot() {
            return fallbackSnapshot;
        }
    }
}
