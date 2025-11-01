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
    public List<Map<String, Object>> getHistory(String projectKey,
                                                String repositorySlug,
                                                Long pullRequestId,
                                                int limit) {
        final int fetchLimit = Math.min(Math.max(limit, 1), 100);

        return ao.executeInTransaction(() -> {
            Query query = Query.select()
                    .order("REVIEW_START_TIME DESC")
                    .limit(fetchLimit);

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

            if (!clauses.isEmpty()) {
                String whereClause = clauses.stream().collect(Collectors.joining(" AND "));
                query = query.where(whereClause, params.toArray());
            }

            AIReviewHistory[] histories = ao.find(AIReviewHistory.class, query);
            log.debug("Fetched {} review history entries (limit={}, filters applied={})",
                    histories.length, fetchLimit, !clauses.isEmpty());

            return Arrays.stream(histories)
                    .map(this::toMap)
                    .collect(Collectors.toList());
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
