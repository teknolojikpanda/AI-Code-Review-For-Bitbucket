package com.example.bitbucket.aicode.api;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.time.Instant;
import java.util.Map;

/**
 * Abstraction for collecting metrics without tying directly to MetricsCollector.
 */
public interface MetricsRecorder {

    @Nonnull
    Instant recordStart(@Nonnull String key);

    void recordEnd(@Nonnull String key, @Nonnull Instant start);

    void increment(@Nonnull String key);

    void recordMetric(@Nonnull String key, @Nullable Object value);

    @Nonnull
    Map<String, Object> snapshot();
}
