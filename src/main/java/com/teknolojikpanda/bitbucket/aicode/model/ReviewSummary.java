package com.teknolojikpanda.bitbucket.aicode.model;

import javax.annotation.Nonnull;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Aggregated summary of findings and severity counts.
 */
public final class ReviewSummary {

    private final Map<SeverityLevel, Integer> counts;
    private final boolean truncated;
    private final List<ReviewFinding> findings;
    private final String impactSummary;
    private final boolean degraded;
    private final int failedChunkCount;

    private ReviewSummary(Builder builder) {
        this.counts = new EnumMap<>(builder.counts);
        this.truncated = builder.truncated;
        this.findings = java.util.Collections.unmodifiableList(builder.findings);
        this.impactSummary = builder.impactSummary;
        this.degraded = builder.degraded;
        this.failedChunkCount = builder.failedChunkCount;
    }

    @Nonnull
    public Map<SeverityLevel, Integer> getCounts() {
        return java.util.Collections.unmodifiableMap(counts);
    }

    public boolean isTruncated() {
        return truncated;
    }

    @Nonnull
    public List<ReviewFinding> getFindings() {
        return findings;
    }

    public int totalCount() {
        return findings.size();
    }

    @Nonnull
    public String getImpactSummary() {
        return impactSummary;
    }

    public boolean isDegraded() {
        return degraded;
    }

    public int getFailedChunkCount() {
        return failedChunkCount;
    }

    public int countFor(@Nonnull SeverityLevel severity) {
        return counts.getOrDefault(Objects.requireNonNull(severity, "severity"), 0);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private final Map<SeverityLevel, Integer> counts = new EnumMap<>(SeverityLevel.class);
        private boolean truncated;
        private List<ReviewFinding> findings = new java.util.ArrayList<>();
        private String impactSummary = "";
        private boolean degraded;
        private int failedChunkCount;

        public Builder addCount(@Nonnull SeverityLevel severity, int count) {
            counts.merge(Objects.requireNonNull(severity, "severity"), count, Integer::sum);
            return this;
        }

        public Builder truncated(boolean value) {
            this.truncated = value;
            return this;
        }

        public Builder findings(@Nonnull List<ReviewFinding> value) {
            this.findings = new java.util.ArrayList<>(Objects.requireNonNull(value, "value"));
            return this;
        }

        public Builder impactSummary(@Nonnull String value) {
            this.impactSummary = Objects.requireNonNull(value, "value");
            return this;
        }

        public Builder degraded(boolean value) {
            this.degraded = value;
            return this;
        }

        public Builder failedChunkCount(int value) {
            this.failedChunkCount = Math.max(0, value);
            return this;
        }

        public ReviewSummary build() {
            return new ReviewSummary(this);
        }
    }
}
