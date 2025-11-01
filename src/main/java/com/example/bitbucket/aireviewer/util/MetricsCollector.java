package com.example.bitbucket.aireviewer.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Collects and aggregates metrics for AI code review operations.
 *
 * Thread-safe implementation that tracks:
 * - Operation timings
 * - Counter metrics
 * - Gauge metrics
 * - Average/min/max statistics
 *
 * Used for monitoring, debugging, and performance analysis.
 */
public class MetricsCollector {

    private static final Logger log = LoggerFactory.getLogger(MetricsCollector.class);

    private final String name;
    private final Map<String, TimingMetric> timings;
    private final Map<String, AtomicLong> counters;
    private final Map<String, Object> gauges;
    private final Instant createdAt;

    /**
     * Creates a new metrics collector.
     *
     * @param name unique name for this collector (e.g., "pr-12345-review")
     */
    public MetricsCollector(@Nonnull String name) {
        this.name = name;
        this.timings = new ConcurrentHashMap<>();
        this.counters = new ConcurrentHashMap<>();
        this.gauges = new ConcurrentHashMap<>();
        this.createdAt = Instant.now();
    }

    /**
     * Records the start of a timed operation.
     *
     * @param operationName name of the operation
     * @return the start time (for passing to recordEnd)
     */
    @Nonnull
    public Instant recordStart(@Nonnull String operationName) {
        log.debug("Metrics [{}] starting operation: {}", name, operationName);
        return Instant.now();
    }

    /**
     * Records the end of a timed operation.
     *
     * @param operationName name of the operation
     * @param startTime the start time returned by recordStart
     */
    public void recordEnd(@Nonnull String operationName, @Nonnull Instant startTime) {
        Instant endTime = Instant.now();
        long durationMs = Duration.between(startTime, endTime).toMillis();
        recordMetric(operationName, durationMs);
        log.debug("Metrics [{}] completed operation: {} in {}ms", name, operationName, durationMs);
    }

    /**
     * Records a metric value (typically a duration in milliseconds).
     *
     * Automatically tracks count, sum, min, max, and average.
     *
     * @param metricName name of the metric
     * @param value the value to record
     */
    public void recordMetric(@Nonnull String metricName, long value) {
        timings.compute(metricName, (k, v) -> {
            if (v == null) {
                return new TimingMetric(value);
            }
            v.add(value);
            return v;
        });
    }

    /**
     * Increments a counter metric by 1.
     *
     * @param counterName name of the counter
     */
    public void incrementCounter(@Nonnull String counterName) {
        incrementCounter(counterName, 1);
    }

    /**
     * Increments a counter metric by a specific amount.
     *
     * @param counterName name of the counter
     * @param delta amount to increment by
     */
    public void incrementCounter(@Nonnull String counterName, long delta) {
        counters.computeIfAbsent(counterName, k -> new AtomicLong(0))
                .addAndGet(delta);
    }

    /**
     * Sets a gauge value (a metric that can go up or down).
     *
     * @param gaugeName name of the gauge
     * @param value the value to set
     */
    public void setGauge(@Nonnull String gaugeName, @Nonnull Object value) {
        gauges.put(gaugeName, value);
    }

    /**
     * Gets the current value of a counter.
     *
     * @param counterName name of the counter
     * @return the current value, or 0 if not found
     */
    public long getCounter(@Nonnull String counterName) {
        AtomicLong counter = counters.get(counterName);
        return counter != null ? counter.get() : 0;
    }

    /**
     * Gets the current value of a gauge.
     *
     * @param gaugeName name of the gauge
     * @return the current value, or null if not found
     */
    public Object getGauge(@Nonnull String gaugeName) {
        return gauges.get(gaugeName);
    }

    /**
     * Appends an entry to a list-valued gauge, creating the list if necessary.
     *
     * @param gaugeName name of the list gauge
     * @param value entry to append
     */
    @SuppressWarnings("unchecked")
    public void appendListEntry(@Nonnull String gaugeName, @Nonnull Map<String, Object> value) {
        gauges.compute(gaugeName, (key, existing) -> {
            CopyOnWriteArrayList<Map<String, Object>> list;
            if (existing instanceof CopyOnWriteArrayList) {
                list = (CopyOnWriteArrayList<Map<String, Object>>) existing;
            } else if (existing instanceof List) {
                list = new CopyOnWriteArrayList<>((List<Map<String, Object>>) existing);
            } else if (existing == null) {
                list = new CopyOnWriteArrayList<>();
            } else {
                list = new CopyOnWriteArrayList<>();
                list.add(new LinkedHashMap<>(Map.of("previousValue", existing)));
            }
            list.add(new LinkedHashMap<>(value));
            return list;
        });
    }

    /**
     * Gets all metrics as a map.
     *
     * @return map of all collected metrics
     */
    @Nonnull
    public Map<String, Object> getMetrics() {
        Map<String, Object> result = new ConcurrentHashMap<>();

        // Add timing metrics
        timings.forEach((key, value) -> {
            Map<String, Object> timingData = new ConcurrentHashMap<>();
            timingData.put("count", value.count);
            timingData.put("totalMs", value.sum);
            timingData.put("avgMs", value.getAverage());
            timingData.put("minMs", value.min);
            timingData.put("maxMs", value.max);
            result.put(key, timingData);
        });

        // Add counters
        counters.forEach((key, value) -> result.put(key, value.get()));

        // Add gauges
        result.putAll(gauges);

        // Add metadata
        result.put("_name", name);
        result.put("_createdAt", createdAt.toString());
        result.put("_elapsedMs", Duration.between(createdAt, Instant.now()).toMillis());

        return result;
    }

    /**
     * Logs all metrics at INFO level.
     */
    public void logMetrics() {
        log.info("=== Metrics Report: {} ===", name);
        log.info("Total elapsed time: {}ms", Duration.between(createdAt, Instant.now()).toMillis());

        if (!timings.isEmpty()) {
            log.info("--- Timing Metrics ---");
            timings.forEach((key, value) -> {
                log.info("  {}: count={}, total={}ms, avg={}ms, min={}ms, max={}ms",
                        key, value.count, value.sum, value.getAverage(), value.min, value.max);
            });
        }

        if (!counters.isEmpty()) {
            log.info("--- Counter Metrics ---");
            counters.forEach((key, value) -> log.info("  {}: {}", key, value.get()));
        }

        if (!gauges.isEmpty()) {
            log.info("--- Gauge Metrics ---");
            gauges.forEach((key, value) -> log.info("  {}: {}", key, value));
        }

        log.info("=== End Metrics Report ===");
    }

    /**
     * Resets all metrics.
     */
    public void reset() {
        log.info("Metrics [{}] reset", name);
        timings.clear();
        counters.clear();
        gauges.clear();
    }

    /**
     * Gets the name of this metrics collector.
     *
     * @return the name
     */
    @Nonnull
    public String getName() {
        return name;
    }

    /**
     * Helper class to track timing statistics.
     */
    private static class TimingMetric {
        private long count;
        private long sum;
        private long min;
        private long max;

        TimingMetric(long initialValue) {
            this.count = 1;
            this.sum = initialValue;
            this.min = initialValue;
            this.max = initialValue;
        }

        synchronized void add(long value) {
            count++;
            sum += value;
            min = Math.min(min, value);
            max = Math.max(max, value);
        }

        long getAverage() {
            return count > 0 ? sum / count : 0;
        }
    }
}
