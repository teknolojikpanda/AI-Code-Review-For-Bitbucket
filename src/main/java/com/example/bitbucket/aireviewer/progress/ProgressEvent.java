package com.example.bitbucket.aireviewer.progress;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable value object describing a single progress milestone within the review pipeline.
 */
public final class ProgressEvent {

    private final long timestamp;
    private final String stage;
    private final int percentComplete;
    private final Map<String, Object> details;

    private ProgressEvent(long timestamp,
                          @Nonnull String stage,
                          int percentComplete,
                          @Nonnull Map<String, Object> details) {
        this.timestamp = timestamp;
        this.stage = stage;
        this.percentComplete = percentComplete;
        this.details = Collections.unmodifiableMap(new LinkedHashMap<>(details));
    }

    public long getTimestamp() {
        return timestamp;
    }

    @Nonnull
    public String getStage() {
        return stage;
    }

    public int getPercentComplete() {
        return percentComplete;
    }

    @Nonnull
    public Map<String, Object> getDetails() {
        return details;
    }

    @Nonnull
    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("timestamp", timestamp);
        map.put("stage", stage);
        map.put("percentComplete", percentComplete);
        if (!details.isEmpty()) {
            map.put("details", details);
        }
        return map;
    }

    @Nonnull
    public static Builder builder(@Nonnull String stage) {
        return new Builder(stage);
    }

    public static final class Builder {
        private final String stage;
        private long timestamp = System.currentTimeMillis();
        private int percentComplete = 0;
        private final Map<String, Object> details = new LinkedHashMap<>();

        private Builder(@Nonnull String stage) {
            this.stage = Objects.requireNonNull(stage, "stage");
        }

        @Nonnull
        public Builder timestamp(long timestamp) {
            this.timestamp = timestamp;
            return this;
        }

        @Nonnull
        public Builder percentComplete(int percentComplete) {
            this.percentComplete = Math.max(0, Math.min(100, percentComplete));
            return this;
        }

        @Nonnull
        public Builder putDetail(@Nonnull String key, @Nullable Object value) {
            if (key != null && value != null) {
                this.details.put(key, value);
            }
            return this;
        }

        @Nonnull
        public Builder details(@Nonnull Map<String, Object> details) {
            this.details.clear();
            this.details.putAll(details);
            return this;
        }

        @Nonnull
        public ProgressEvent build() {
            return new ProgressEvent(timestamp, stage, percentComplete, details);
        }
    }
}
