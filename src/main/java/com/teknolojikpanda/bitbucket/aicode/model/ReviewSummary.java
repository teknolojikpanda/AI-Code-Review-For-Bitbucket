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

    private ReviewSummary(Builder builder) {
        this.counts = new EnumMap<>(builder.counts);
        this.truncated = builder.truncated;
        this.findings = java.util.Collections.unmodifiableList(builder.findings);
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

        public ReviewSummary build() {
            return new ReviewSummary(this);
        }
    }
}
