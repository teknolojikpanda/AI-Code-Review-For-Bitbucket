package com.example.bitbucket.aireviewer.service;

import com.atlassian.activeobjects.external.ActiveObjects;
import com.atlassian.plugin.spring.scanner.annotation.imports.ComponentImport;
import com.example.bitbucket.aireviewer.ao.AIReviewChunk;
import com.example.bitbucket.aireviewer.ao.AIReviewHistory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import javax.inject.Inject;
import javax.inject.Singleton;
import javax.inject.Named;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

import net.java.ao.Query;

/**
 * Provides read-only access to stored AI review history.
 */
@Named
@Singleton
public class ReviewHistoryService {

    private static final Logger log = LoggerFactory.getLogger(ReviewHistoryService.class);

    private final ActiveObjects ao;

    @Inject
    public ReviewHistoryService(@ComponentImport ActiveObjects ao) {
        this.ao = Objects.requireNonNull(ao, "activeObjects");
    }

    /**
     * Retrieves review history entries ordered by start time descending.
     *
     * @param projectKey optional project filter
     * @param repositorySlug optional repository filter
     * @param pullRequestId optional pull request filter
     * @param limit maximum number of rows to return (1-100)
     * @return list of history records serialized as maps for JSON rendering
     */
    @Nonnull
    public Page<Map<String, Object>> getHistory(String projectKey,
                                                String repositorySlug,
                                                Long pullRequestId,
                                                Long since,
                                                Long until,
                                                int limit,
                                                int offset) {
        final int pageSize = Math.min(Math.max(limit, 1), 100);
        final int start = Math.max(offset, 0);

        return ao.executeInTransaction(() -> {
            Query baseQuery = Query.select()
                    .order("REVIEW_START_TIME DESC");

            List<String> clauses = new ArrayList<>();
            List<Object> params = new ArrayList<>();

            if (projectKey != null && !projectKey.trim().isEmpty()) {
                clauses.add("PROJECT_KEY = ?");
                params.add(projectKey.trim());
            }
            if (repositorySlug != null && !repositorySlug.trim().isEmpty()) {
                clauses.add("REPOSITORY_SLUG = ?");
                params.add(repositorySlug.trim());
            }
            if (pullRequestId != null && pullRequestId > 0) {
                clauses.add("PULL_REQUEST_ID = ?");
                params.add(pullRequestId);
            }
            if (since != null && since > 0) {
                clauses.add("REVIEW_START_TIME >= ?");
                params.add(since);
            }
            if (until != null && until > 0) {
                clauses.add("REVIEW_START_TIME <= ?");
                params.add(until);
            }

            if (!clauses.isEmpty()) {
                String whereClause = clauses.stream().collect(Collectors.joining(" AND "));
                baseQuery = baseQuery.where(whereClause, params.toArray());
            }

            int total = ao.count(AIReviewHistory.class, baseQuery);
            Query pagedQuery = baseQuery.limit(pageSize).offset(start);
            AIReviewHistory[] histories = ao.find(AIReviewHistory.class, pagedQuery);
            log.debug("Fetched {} review history entries (limit={}, offset={}, filters applied={})",
                    histories.length, pageSize, start, !clauses.isEmpty());

            List<Map<String, Object>> data = Arrays.stream(histories)
                    .map(this::toMap)
                    .collect(Collectors.toList());
            return new Page<>(data, total, pageSize, start);
        });
    }

    @Nonnull
    public Optional<Map<String, Object>> getHistoryById(long historyId) {
        return ao.executeInTransaction(() -> {
            AIReviewHistory[] rows = ao.find(AIReviewHistory.class,
                    Query.select().where("ID = ?", historyId));
            if (rows.length == 0) {
                return Optional.empty();
            }

            AIReviewHistory history = rows[0];
            Map<String, Object> map = toMap(history);
            map.put("chunkCount", countChunks(historyId));
            return Optional.of(map);
        });
    }

    @Nonnull
    public Map<String, Object> getChunks(long historyId, int limit) {
        final int fetchLimit = Math.min(Math.max(limit, 1), 500);
        return ao.executeInTransaction(() -> {
            int total = countChunks(historyId);
            Query query = Query.select()
                    .where("HISTORY_ID = ?", historyId)
                    .order("SEQUENCE ASC, ID ASC")
                    .limit(fetchLimit);
            AIReviewChunk[] chunkEntities = ao.find(AIReviewChunk.class, query);
            List<Map<String, Object>> entries = Arrays.stream(chunkEntities)
                    .map(this::toChunkMap)
                    .collect(Collectors.toList());

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("chunks", entries);
            result.put("count", entries.size());
            result.put("total", total);
            result.put("limit", fetchLimit);
            return result;
        });
    }

    @Nonnull
    public Map<String, Object> getMetricsSummary(String projectKey,
                                                 String repositorySlug,
                                                 Long pullRequestId,
                                                 Long since,
                                                 Long until) {
        final long generatedAt = System.currentTimeMillis();

        return ao.executeInTransaction(() -> {
            Query query = Query.select();
            List<String> clauses = new ArrayList<>();
            List<Object> params = new ArrayList<>();

            if (projectKey != null && !projectKey.trim().isEmpty()) {
                clauses.add("PROJECT_KEY = ?");
                params.add(projectKey.trim());
            }
            if (repositorySlug != null && !repositorySlug.trim().isEmpty()) {
                clauses.add("REPOSITORY_SLUG = ?");
                params.add(repositorySlug.trim());
            }
            if (pullRequestId != null && pullRequestId > 0) {
                clauses.add("PULL_REQUEST_ID = ?");
                params.add(pullRequestId);
            }
            if (since != null && since > 0) {
                clauses.add("REVIEW_START_TIME >= ?");
                params.add(since);
            }
            if (until != null && until > 0) {
                clauses.add("REVIEW_START_TIME <= ?");
                params.add(until);
            }

            if (!clauses.isEmpty()) {
                query = query.where(String.join(" AND ", clauses), params.toArray());
            }

            AIReviewHistory[] histories = ao.find(AIReviewHistory.class, query);

            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("generatedAt", generatedAt);
            summary.put("totalReviews", histories.length);
            summary.put("filtersApplied", !clauses.isEmpty());
            summary.put("filter", buildFilterDescriptor(projectKey, repositorySlug, pullRequestId, since, until));

            if (histories.length == 0) {
                summary.put("statusCounts", Collections.emptyMap());
                summary.put("issueTotals", emptyIssueTotals());
                summary.put("durationSeconds", Collections.emptyMap());
                summary.put("fallback", emptyFallbackTotals());
                summary.put("chunkTotals", emptyChunkTotals());
                return summary;
            }

            Map<String, Long> statusCounts = new LinkedHashMap<>();
            statusCounts.put("SUCCESS", 0L);
            statusCounts.put("PARTIAL", 0L);
            statusCounts.put("FAILED", 0L);
            statusCounts.put("SKIPPED", 0L);

            long critical = 0;
            long high = 0;
            long medium = 0;
            long low = 0;
            long totalIssues = 0;

            long totalChunks = 0;
            long successfulChunks = 0;
            long failedChunks = 0;

            long primaryInvocations = 0;
            long primarySuccesses = 0;
            long primaryFailures = 0;
            long fallbackInvocations = 0;
            long fallbackSuccesses = 0;
            long fallbackFailures = 0;
            long fallbackTriggered = 0;

            List<Double> durations = new ArrayList<>();

            for (AIReviewHistory history : histories) {
                String status = normalizeStatus(history.getReviewStatus());
                statusCounts.merge(status, 1L, Long::sum);

                critical += history.getCriticalIssues();
                high += history.getHighIssues();
                medium += history.getMediumIssues();
                low += history.getLowIssues();
                totalIssues += history.getTotalIssuesFound();

                totalChunks += Math.max(0, history.getTotalChunks());
                successfulChunks += Math.max(0, history.getSuccessfulChunks());
                failedChunks += Math.max(0, history.getFailedChunks());

                primaryInvocations += Math.max(0, history.getPrimaryModelInvocations());
                primarySuccesses += Math.max(0, history.getPrimaryModelSuccesses());
                primaryFailures += Math.max(0, history.getPrimaryModelFailures());
                fallbackInvocations += Math.max(0, history.getFallbackModelInvocations());
                fallbackSuccesses += Math.max(0, history.getFallbackModelSuccesses());
                fallbackFailures += Math.max(0, history.getFallbackModelFailures());
                fallbackTriggered += Math.max(0, history.getFallbackTriggered());

                double durationSeconds = resolveDurationSeconds(history);
                if (durationSeconds > 0) {
                    durations.add(durationSeconds);
                }
            }

            summary.put("statusCounts", statusCounts);
            summary.put("issueTotals", buildIssueTotals(totalIssues, critical, high, medium, low, histories.length));
            summary.put("durationSeconds", buildDurationSummary(durations));
            summary.put("fallback", buildFallbackTotals(fallbackTriggered,
                    primaryInvocations, primarySuccesses, primaryFailures,
                    fallbackInvocations, fallbackSuccesses, fallbackFailures));
            summary.put("chunkTotals", buildChunkTotals(totalChunks, successfulChunks, failedChunks));

            return summary;
        });
    }

    @Nonnull
    private Map<String, Object> toMap(@Nonnull AIReviewHistory history) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", history.getID());
        map.put("pullRequestId", history.getPullRequestId());
        map.put("projectKey", history.getProjectKey());
        map.put("repositorySlug", history.getRepositorySlug());
        map.put("reviewStatus", history.getReviewStatus());
        map.put("reviewOutcome", history.getReviewOutcome());
        map.put("modelUsed", history.getModelUsed());
        map.put("reviewStartTime", history.getReviewStartTime());
        map.put("reviewEndTime", history.getReviewEndTime());
        map.put("analysisTimeSeconds", history.getAnalysisTimeSeconds());
        map.put("totalIssuesFound", history.getTotalIssuesFound());
        map.put("criticalIssues", history.getCriticalIssues());
        map.put("highIssues", history.getHighIssues());
        map.put("mediumIssues", history.getMediumIssues());
        map.put("lowIssues", history.getLowIssues());
        map.put("resolvedIssues", history.getResolvedIssuesCount());
        map.put("newIssues", history.getNewIssuesCount());
        map.put("totalFiles", history.getTotalFiles());
        map.put("filesReviewed", history.getFilesReviewed());
        map.put("commentsPosted", history.getCommentsPosted());
        map.put("failedChunks", history.getFailedChunks());
        map.put("successfulChunks", history.getSuccessfulChunks());
        map.put("totalChunks", history.getTotalChunks());
        map.put("profileKey", history.getProfileKey());
        map.put("autoApproveEnabled", history.isAutoApproveEnabled());
        map.put("primaryModelInvocations", history.getPrimaryModelInvocations());
        map.put("primaryModelSuccesses", history.getPrimaryModelSuccesses());
        map.put("primaryModelFailures", history.getPrimaryModelFailures());
        map.put("fallbackModelInvocations", history.getFallbackModelInvocations());
        map.put("fallbackModelSuccesses", history.getFallbackModelSuccesses());
        map.put("fallbackModelFailures", history.getFallbackModelFailures());
        map.put("fallbackTriggered", history.getFallbackTriggered());
        map.put("metricsSnapshot", safeMetrics(history.getMetricsJson()));

        long start = history.getReviewStartTime();
        long end = history.getReviewEndTime();
        if (start > 0 && end > start) {
            map.put("durationSeconds", (end - start) / 1000);
        } else {
            map.put("durationSeconds", null);
        }

        boolean hasBlocking = history.getCriticalIssues() > 0 || history.getHighIssues() > 0;
        map.put("hasBlockingIssues", hasBlocking);

        return map;
    }

    private String safeMetrics(String metricsJson) {
        if (metricsJson == null) {
            return "";
        }
        return metricsJson.length() > 10_000
                ? metricsJson.substring(0, 10_000)
                : metricsJson;
    }

    private int countChunks(long historyId) {
        return ao.count(AIReviewChunk.class, Query.select().where("HISTORY_ID = ?", historyId));
    }

    private Map<String, Object> toChunkMap(@Nonnull AIReviewChunk chunk) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", chunk.getID());
        map.put("chunkId", chunk.getChunkId());
        map.put("sequence", chunk.getSequence());
        map.put("role", chunk.getRole());
        map.put("model", chunk.getModel());
        map.put("endpoint", chunk.getEndpoint());
        map.put("attempts", chunk.getAttempts());
        map.put("retries", chunk.getRetries());
        map.put("durationMs", chunk.getDurationMs());
        map.put("success", chunk.isSuccess());
        map.put("modelNotFound", chunk.isModelNotFound());
        String lastError = chunk.getLastError();
        if (lastError != null && !lastError.trim().isEmpty()) {
            map.put("lastError", truncate(lastError, 2048));
        }
        return map;
    }

    private Map<String, Object> buildFilterDescriptor(String projectKey,
                                                      String repositorySlug,
                                                      Long pullRequestId,
                                                      Long since,
                                                      Long until) {
        Map<String, Object> filter = new LinkedHashMap<>();
        if (projectKey != null && !projectKey.trim().isEmpty()) {
            filter.put("projectKey", projectKey.trim());
        }
        if (repositorySlug != null && !repositorySlug.trim().isEmpty()) {
            filter.put("repositorySlug", repositorySlug.trim());
        }
        if (pullRequestId != null && pullRequestId > 0) {
            filter.put("pullRequestId", pullRequestId);
        }
        if (since != null && since > 0) {
            filter.put("since", since);
        }
        if (until != null && until > 0) {
            filter.put("until", until);
        }
        return filter.isEmpty() ? Collections.emptyMap() : filter;
    }

    private Map<String, Object> emptyIssueTotals() {
        return buildIssueTotals(0, 0, 0, 0, 0, 0);
    }

    private Map<String, Object> emptyFallbackTotals() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("triggered", 0L);
        map.put("primaryInvocations", 0L);
        map.put("primarySuccesses", 0L);
        map.put("primaryFailures", 0L);
        map.put("primarySuccessRate", null);
        map.put("fallbackInvocations", 0L);
        map.put("fallbackSuccesses", 0L);
        map.put("fallbackFailures", 0L);
        map.put("fallbackSuccessRate", null);
        return map;
    }

    private Map<String, Object> emptyChunkTotals() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("totalChunks", 0L);
        map.put("successfulChunks", 0L);
        map.put("failedChunks", 0L);
        map.put("successRate", null);
        return map;
    }

    private Map<String, Object> buildIssueTotals(long totalIssues,
                                                 long critical,
                                                 long high,
                                                 long medium,
                                                 long low,
                                                 int reviewCount) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("totalIssues", totalIssues);
        map.put("critical", critical);
        map.put("high", high);
        map.put("medium", medium);
        map.put("low", low);
        map.put("avgIssuesPerReview", reviewCount > 0 ? (double) totalIssues / (double) reviewCount : 0d);
        return map;
    }

    private Map<String, Object> buildDurationSummary(List<Double> durations) {
        if (durations == null || durations.isEmpty()) {
            return Collections.emptyMap();
        }
        List<Double> sorted = new ArrayList<>(durations);
        Collections.sort(sorted);
        double sum = 0d;
        for (Double value : sorted) {
            sum += value;
        }
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("count", sorted.size());
        map.put("average", sum / sorted.size());
        map.put("min", sorted.get(0));
        map.put("max", sorted.get(sorted.size() - 1));
        map.put("p50", percentile(sorted, 0.50));
        map.put("p95", percentile(sorted, 0.95));
        return map;
    }

    private Map<String, Object> buildFallbackTotals(long triggered,
                                                    long primaryInvocations,
                                                    long primarySuccesses,
                                                    long primaryFailures,
                                                    long fallbackInvocations,
                                                    long fallbackSuccesses,
                                                    long fallbackFailures) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("triggered", triggered);
        map.put("primaryInvocations", primaryInvocations);
        map.put("primarySuccesses", primarySuccesses);
        map.put("primaryFailures", primaryFailures);
        map.put("primarySuccessRate", computeRate(primarySuccesses, primaryInvocations));
        map.put("fallbackInvocations", fallbackInvocations);
        map.put("fallbackSuccesses", fallbackSuccesses);
        map.put("fallbackFailures", fallbackFailures);
        map.put("fallbackSuccessRate", computeRate(fallbackSuccesses, fallbackInvocations));
        return map;
    }

    private Map<String, Object> buildChunkTotals(long totalChunks,
                                                 long successfulChunks,
                                                 long failedChunks) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("totalChunks", totalChunks);
        map.put("successfulChunks", successfulChunks);
        map.put("failedChunks", failedChunks);
        map.put("successRate", computeRate(successfulChunks, totalChunks));
        return map;
    }

    private Double computeRate(long numerator, long denominator) {
        if (denominator <= 0) {
            return null;
        }
        return (double) numerator / (double) denominator;
    }

    private double percentile(List<Double> sortedValues, double percentile) {
        if (sortedValues.isEmpty()) {
            return 0d;
        }
        if (sortedValues.size() == 1) {
            return sortedValues.get(0);
        }
        double index = percentile * (sortedValues.size() - 1);
        int lower = (int) Math.floor(index);
        int upper = (int) Math.ceil(index);
        if (lower == upper) {
            return sortedValues.get(lower);
        }
        double fraction = index - lower;
        double lowerValue = sortedValues.get(lower);
        double upperValue = sortedValues.get(upper);
        return lowerValue + (upperValue - lowerValue) * fraction;
    }

    private String normalizeStatus(String status) {
        if (status == null || status.trim().isEmpty()) {
            return "UNKNOWN";
        }
        String normalized = status.trim().toUpperCase();
        if (!normalized.equals("SUCCESS") &&
            !normalized.equals("PARTIAL") &&
            !normalized.equals("FAILED") &&
            !normalized.equals("SKIPPED")) {
            return normalized;
        }
        return normalized;
    }

    private double resolveDurationSeconds(@Nonnull AIReviewHistory history) {
        double recorded = history.getAnalysisTimeSeconds();
        if (recorded > 0) {
            return recorded;
        }
        long start = history.getReviewStartTime();
        long end = history.getReviewEndTime();
        if (start > 0 && end > start) {
            return (end - start) / 1000.0;
        }
        return -1d;
    }

    private String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.length() <= max) {
            return trimmed;
        }
        return trimmed.substring(0, Math.max(0, max));
    }
}
