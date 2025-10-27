package com.example.bitbucket.aicode.core;

import com.example.bitbucket.aicode.api.MetricsRecorder;
import com.example.bitbucket.aireviewer.util.MetricsCollector;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * Adapter allowing new components to use the legacy MetricsCollector through MetricsRecorder interface.
 */
public final class MetricsRecorderAdapter implements MetricsRecorder {

    private final MetricsCollector delegate;

    public MetricsRecorderAdapter(@Nonnull MetricsCollector delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    @Nonnull
    @Override
    public Instant recordStart(@Nonnull String key) {
        return delegate.recordStart(Objects.requireNonNull(key, "key"));
    }

    @Override
    public void recordEnd(@Nonnull String key, @Nonnull Instant start) {
        delegate.recordEnd(Objects.requireNonNull(key, "key"), Objects.requireNonNull(start, "start"));
    }

    @Override
    public void increment(@Nonnull String key) {
        delegate.incrementCounter(Objects.requireNonNull(key, "key"));
    }

    @Override
    public void recordMetric(@Nonnull String key, @Nullable Object value) {
        Objects.requireNonNull(key, "key");
        if (value instanceof Number) {
            delegate.recordMetric(key, ((Number) value).longValue());
        } else if (value != null) {
            delegate.setGauge(key, value);
        }
    }

    @Nonnull
    @Override
    public Map<String, Object> snapshot() {
        return delegate.getMetrics();
    }
}
