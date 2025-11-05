package com.teknolojikpanda.bitbucket.aireviewer.util;

import com.teknolojikpanda.bitbucket.aireviewer.dto.ReviewIssue;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Aggregates review issues into high level insights that power summary rendering
 * and future REST responses.
 */
public final class SummaryInsights {

    private final Map<ReviewIssue.Severity, Long> severityCounts;
    private final List<CategorySummary> topCategories;
    private final int totalIssues;
    private final int blockingIssueCount;
    private final int newIssueCount;
    private final int resolvedIssueCount;

    private SummaryInsights(Builder builder) {
        this.severityCounts = Collections.unmodifiableMap(new EnumMap<>(builder.severityCounts));
        this.topCategories = Collections.unmodifiableList(new ArrayList<>(builder.topCategories));
        this.totalIssues = builder.totalIssues;
        this.blockingIssueCount = builder.blockingIssueCount;
        this.newIssueCount = builder.newIssueCount;
        this.resolvedIssueCount = builder.resolvedIssueCount;
    }

    @Nonnull
    public Map<ReviewIssue.Severity, Long> getSeverityCounts() {
        return severityCounts;
    }

    @Nonnull
    public List<CategorySummary> getTopCategories() {
        return topCategories;
    }

    public int getTotalIssues() {
        return totalIssues;
    }

    public int getBlockingIssueCount() {
        return blockingIssueCount;
    }

    public int getNewIssueCount() {
        return newIssueCount;
    }

    public int getResolvedIssueCount() {
        return resolvedIssueCount;
    }

    public boolean hasBlockingIssues() {
        return blockingIssueCount > 0;
    }

    public boolean hasOnlyLowOrInfoIssues() {
        long total = severityCounts.values().stream().mapToLong(Long::longValue).sum();
        long lowAndInfo = severityCounts.getOrDefault(ReviewIssue.Severity.LOW, 0L)
                + severityCounts.getOrDefault(ReviewIssue.Severity.INFO, 0L);
        return total > 0 && lowAndInfo == total;
    }

    public boolean isEmpty() {
        return totalIssues == 0;
    }

    @Nonnull
    public static SummaryInsights analyze(@Nonnull List<ReviewIssue> issues,
                                          @Nonnull List<ReviewIssue> resolved,
                                          @Nonnull List<ReviewIssue> newlyIntroduced) {
        Objects.requireNonNull(issues, "issues");
        Objects.requireNonNull(resolved, "resolved");
        Objects.requireNonNull(newlyIntroduced, "newlyIntroduced");

        Builder builder = new Builder();

        EnumMap<ReviewIssue.Severity, Long> counts = new EnumMap<>(ReviewIssue.Severity.class);
        for (ReviewIssue.Severity severity : ReviewIssue.Severity.values()) {
            counts.put(severity, 0L);
        }

        Map<String, Long> categories = new LinkedHashMap<>();
        for (ReviewIssue issue : issues) {
            ReviewIssue.Severity severity = Optional.ofNullable(issue.getSeverity())
                    .orElse(ReviewIssue.Severity.MEDIUM);
            counts.put(severity, counts.getOrDefault(severity, 0L) + 1);

            String type = Optional.ofNullable(issue.getType())
                    .map(value -> value.trim().isEmpty() ? "Uncategorised" : value)
                    .orElse("Uncategorised");
            categories.put(type, categories.getOrDefault(type, 0L) + 1);
        }

        builder.severityCounts.putAll(counts);
        builder.totalIssues = issues.size();
        builder.blockingIssueCount = Math.toIntExact(
                counts.getOrDefault(ReviewIssue.Severity.CRITICAL, 0L)
                        + counts.getOrDefault(ReviewIssue.Severity.HIGH, 0L));
        builder.newIssueCount = newlyIntroduced.size();
        builder.resolvedIssueCount = resolved.size();

        builder.topCategories = categories.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue(Comparator.reverseOrder()))
                .limit(5)
                .map(entry -> new CategorySummary(entry.getKey(), entry.getValue().intValue()))
                .collect(Collectors.toList());

        return builder.build();
    }

    @Nonnull
    public static SummaryInsights empty() {
        return new Builder().build();
    }

    /**
     * Represents the frequency of an issue category/type.
     */
    public static final class CategorySummary {
        private final String category;
        private final int count;

        CategorySummary(String category, int count) {
            this.category = category;
            this.count = count;
        }

        @Nonnull
        public String getCategory() {
            return category;
        }

        public int getCount() {
            return count;
        }

        @Nonnull
        public String getDisplayName() {
            return Optional.ofNullable(category)
                    .map(value -> value.trim().isEmpty() ? "Uncategorised" : value)
                    .orElse("Uncategorised");
        }
    }

    private static final class Builder {
        private final EnumMap<ReviewIssue.Severity, Long> severityCounts = new EnumMap<>(ReviewIssue.Severity.class);
        private List<CategorySummary> topCategories = Collections.emptyList();
        private int totalIssues = 0;
        private int blockingIssueCount = 0;
        private int newIssueCount = 0;
        private int resolvedIssueCount = 0;

        private SummaryInsights build() {
            return new SummaryInsights(this);
        }
    }
}

