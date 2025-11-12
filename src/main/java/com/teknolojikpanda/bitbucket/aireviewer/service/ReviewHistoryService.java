package com.teknolojikpanda.bitbucket.aireviewer.service;

import com.atlassian.activeobjects.external.ActiveObjects;
import com.atlassian.plugin.spring.scanner.annotation.imports.ComponentImport;
import com.teknolojikpanda.bitbucket.aireviewer.ao.AIReviewChunk;
import com.teknolojikpanda.bitbucket.aireviewer.ao.AIReviewHistory;
import com.teknolojikpanda.bitbucket.aireviewer.util.ChunkTelemetryUtil;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;
import javax.inject.Named;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.concurrent.TimeUnit;

import net.java.ao.DBParam;
import net.java.ao.Query;

/**
 * Provides read-only access to stored AI review history.
 */
@Named
@Singleton
public class ReviewHistoryService {

    private static final Logger log = LoggerFactory.getLogger(ReviewHistoryService.class);
    private static final ObjectMapper PROGRESS_MAPPER = new ObjectMapper();
    private static final TypeReference<List<Map<String, Object>>> PROGRESS_TYPE = new TypeReference<List<Map<String, Object>>>() {};

    private final ActiveObjects ao;
    private final ZoneId zoneId = ZoneId.systemDefault();

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
        final boolean fetchAll = limit <= 0;
        final int pageSize = fetchAll ? Integer.MAX_VALUE : Math.min(Math.max(limit, 1), 100);
        final int start = fetchAll ? 0 : Math.max(offset, 0);

        return ao.executeInTransaction(() -> {
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

            Query countQuery = Query.select();
            Query dataQuery = Query.select().order("REVIEW_START_TIME DESC");

            if (!clauses.isEmpty()) {
                String whereClause = String.join(" AND ", clauses);
                countQuery = countQuery.where(whereClause, params.toArray());
                dataQuery = dataQuery.where(whereClause, params.toArray());
            }

            int total = fetchAll ? 0 : ao.count(AIReviewHistory.class, countQuery);
            Query pagedQuery = fetchAll ? dataQuery : dataQuery.limit(pageSize).offset(start);
            AIReviewHistory[] histories = ao.find(AIReviewHistory.class, pagedQuery);
            if (fetchAll) {
                total = histories.length;
            }
            log.debug("Fetched {} review history entries (limit={}, offset={}, filters applied={}, all={})",
                    histories.length, fetchAll ? -1 : pageSize, fetchAll ? 0 : start, !clauses.isEmpty(), fetchAll);

            List<Map<String, Object>> data = Arrays.stream(histories)
                    .map(this::toMap)
                    .collect(Collectors.toList());
            return new Page<>(data, total, fetchAll ? 0 : pageSize, start);
        });
    }

    @Nonnull
    public Page<Map<String, Object>> getRecentSummaries(@Nonnull String projectKey,
                                                        @Nonnull String repositorySlug,
                                                        long pullRequestId,
                                                        int limit,
                                                        int offset) {
        if (pullRequestId <= 0) {
            final int pageSize = Math.min(Math.max(limit, 1), 50);
            final int start = Math.max(offset, 0);
            return new Page<>(Collections.emptyList(), 0, pageSize, start);
        }

        String normalizedProjectKey = projectKey.trim();
        String normalizedSlug = repositorySlug.trim();
        final int pageSize = Math.min(Math.max(limit, 1), 50);
        final int start = Math.max(offset, 0);

        return ao.executeInTransaction(() -> {
            String whereClause = "PROJECT_KEY = ? AND REPOSITORY_SLUG = ? AND PULL_REQUEST_ID = ?";
            Object[] params = { normalizedProjectKey, normalizedSlug, pullRequestId };

            int total = ao.count(AIReviewHistory.class, Query.select().where(whereClause, params));
            AIReviewHistory[] histories = ao.find(AIReviewHistory.class,
                    Query.select()
                            .where(whereClause, params)
                            .order("REVIEW_START_TIME DESC")
                            .limit(pageSize)
                            .offset(start));

            List<Map<String, Object>> values = Arrays.stream(histories)
                    .map(this::toSummary)
                    .collect(Collectors.toList());
            return new Page<>(values, total, pageSize, start);
        });
    }

    @Nonnull
    public List<Map<String, Object>> getDailySummary(String projectKey,
                                                     String repositorySlug,
                                                     Long pullRequestId,
                                                     Long since,
                                                     Long until,
                                                     int limit) {
        final int maxDays = Math.max(limit, 1);
        return ao.executeInTransaction(() -> {
            Query query = Query.select().order("REVIEW_START_TIME DESC");
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
            Map<LocalDate, DailyStats> daily = new HashMap<>();
            for (AIReviewHistory history : histories) {
                long start = history.getReviewStartTime();
                if (start <= 0) {
                    continue;
                }
                LocalDate date = Instant.ofEpochMilli(start)
                        .atZone(zoneId)
                        .toLocalDate();
                DailyStats stats = daily.computeIfAbsent(date, d -> new DailyStats());
                stats.reviews++;
                stats.totalDurationSeconds += Math.max(0d, resolveDurationSeconds(history));
                stats.totalIssues += Math.max(0, history.getTotalIssuesFound());
                stats.critical += Math.max(0, history.getCriticalIssues());
                stats.high += Math.max(0, history.getHighIssues());
                stats.medium += Math.max(0, history.getMediumIssues());
                stats.low += Math.max(0, history.getLowIssues());
            }

            return daily.entrySet().stream()
                    .sorted(Map.Entry.<LocalDate, DailyStats>comparingByKey().reversed())
                    .limit(maxDays)
                    .map(entry -> {
                        LocalDate date = entry.getKey();
                        DailyStats stats = entry.getValue();
                        Map<String, Object> map = new LinkedHashMap<>();
                        map.put("date", date.toString());
                        map.put("reviewCount", stats.reviews);
                        map.put("totalIssues", stats.totalIssues);
                        map.put("criticalIssues", stats.critical);
                        map.put("highIssues", stats.high);
                        map.put("mediumIssues", stats.medium);
                        map.put("lowIssues", stats.low);
                        map.put("avgDurationSeconds", stats.reviews > 0
                                ? stats.totalDurationSeconds / stats.reviews
                                : 0d);
                        return map;
                    })
                    .collect(Collectors.toList());
        });
    }

    /**
     * Returns the most recent review history entry for a pull request, if any.
     *
     * @param pullRequestId the pull request identifier
     * @return optional containing the latest history entry
     */
    @Nonnull
    public Optional<AIReviewHistory> findLatestForPullRequest(long pullRequestId) {
        if (pullRequestId <= 0) {
            return Optional.empty();
        }
        return ao.executeInTransaction(() -> {
            AIReviewHistory[] histories = ao.find(AIReviewHistory.class,
                    Query.select()
                            .where("PULL_REQUEST_ID = ?", pullRequestId)
                            .order("REVIEW_START_TIME DESC")
                            .limit(1));
            return histories.length > 0 ? Optional.of(histories[0]) : Optional.empty();
        });
    }

    @Nonnull
    public Optional<AIReviewHistory> findEntityById(long historyId) {
        if (historyId <= 0 || historyId > Integer.MAX_VALUE) {
            return Optional.empty();
        }
        int id = (int) historyId;
        return ao.executeInTransaction(() -> Optional.ofNullable(ao.get(AIReviewHistory.class, id)));
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
            ChunkAggregation chunkAggregation = new ChunkAggregation();
            CircuitAggregation circuitAggregation = new CircuitAggregation();

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

                Map<String, Object> metricsMap = ChunkTelemetryUtil.readMetricsMap(history.getMetricsJson());
                List<Map<String, Object>> telemetryEntries = metricsMap.isEmpty()
                        ? Collections.emptyList()
                        : ChunkTelemetryUtil.extractEntries(metricsMap);
                if (!telemetryEntries.isEmpty()) {
                    for (Map<String, Object> entry : telemetryEntries) {
                        chunkAggregation.accept(entry);
                    }
                } else {
                    AIReviewChunk[] chunkEntities = history.getChunks();
                    if (chunkEntities != null) {
                        for (AIReviewChunk chunk : chunkEntities) {
                            chunkAggregation.accept(chunk);
                        }
                    }
                }

                circuitAggregation.accept(metricsMap);
            }

            summary.put("statusCounts", statusCounts);
            summary.put("issueTotals", buildIssueTotals(totalIssues, critical, high, medium, low, histories.length));
            summary.put("durationSeconds", buildDurationSummary(durations));
            summary.put("fallback", buildFallbackTotals(fallbackTriggered,
                    primaryInvocations, primarySuccesses, primaryFailures,
                    fallbackInvocations, fallbackSuccesses, fallbackFailures));
            summary.put("chunkTotals", buildChunkTotals(totalChunks, successfulChunks, failedChunks));
            summary.put("ioTotals", buildIoTotals(
                    chunkAggregation.requestBytes,
                    chunkAggregation.responseBytes,
                    chunkAggregation.chunkCount,
                    chunkAggregation.timeoutCount,
                    chunkAggregation.statusCounts));
            summary.put("breaker", circuitAggregation.toMap());

            return summary;
        });
    }

    @Nonnull
    public Map<String, Object> backfillChunkTelemetry(int limit) {
        final int batchLimit = limit < 0 ? 0 : limit;
        return ao.executeInTransaction(() -> {
            Query query = Query.select().order("REVIEW_START_TIME DESC");
            if (batchLimit > 0) {
                query = query.limit(batchLimit);
            }
            AIReviewHistory[] histories = ao.find(AIReviewHistory.class, query);
            int scanned = histories.length;
            int processed = 0;
            int chunksCreated = 0;

            for (AIReviewHistory history : histories) {
                AIReviewChunk[] existing = history.getChunks();
                if (existing != null && existing.length > 0) {
                    continue;
                }
                List<Map<String, Object>> entries = ChunkTelemetryUtil.extractEntriesFromJson(history.getMetricsJson());
                if (entries.isEmpty()) {
                    continue;
                }
                int sequence = 0;
                int createdForHistory = 0;
                for (Map<String, Object> entry : entries) {
                    String chunkId = limitString(entry.get("chunkId"), 191);
                    if (chunkId == null || chunkId.isEmpty()) {
                        continue;
                    }
                    AIReviewChunk chunk = ao.create(
                            AIReviewChunk.class,
                            new DBParam("HISTORY_ID", history.getID()),
                            new DBParam("CHUNK_ID", chunkId));
                    chunk.setHistory(history);
                    chunk.setChunkId(chunkId);
                    chunk.setRole(limitString(entry.get("role"), 64));
                    chunk.setModel(limitString(entry.get("model"), 255));
                    chunk.setEndpoint(limitString(entry.get("endpoint"), 255));
                    chunk.setSequence(sequence++);
                    chunk.setAttempts(safeLongToInt(asLong(entry.get("attempts"), 0)));
                    chunk.setRetries(safeLongToInt(asLong(entry.get("retries"), 0)));
                    chunk.setDurationMs(asLong(entry.get("durationMs"), 0));
                    chunk.setSuccess(asBoolean(entry.get("success"), false));
                    chunk.setModelNotFound(asBoolean(entry.get("modelNotFound"), false));
                    chunk.setRequestBytes(asLong(entry.get("requestBytes"), 0));
                    chunk.setResponseBytes(asLong(entry.get("responseBytes"), 0));
                    chunk.setStatusCode(safeLongToInt(asLong(entry.get("statusCode"), 0)));
                    chunk.setTimeout(asBoolean(entry.get("timeout"), false));
                    chunk.setLastError(limitString(entry.get("lastError"), 4096));
                    chunk.save();
                    createdForHistory++;
                }
                if (createdForHistory > 0) {
                    processed++;
                    chunksCreated += createdForHistory;
                }
            }

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("historiesScanned", scanned);
            result.put("historiesUpdated", processed);
            result.put("chunksCreated", chunksCreated);
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
        map.put("commitId", history.getCommitId());
        map.put("reviewStatus", history.getReviewStatus());
        map.put("reviewOutcome", history.getReviewOutcome());
        map.put("modelUsed", history.getModelUsed());
        map.put("reviewStartTime", history.getReviewStartTime());
        map.put("reviewEndTime", history.getReviewEndTime());
        map.put("fromCommit", history.getFromCommit());
        map.put("toCommit", history.getToCommit());
        map.put("pullRequestVersion", history.getPullRequestVersion());
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
        String metricsJson = history.getMetricsJson();
        List<Map<String, Object>> progressEntries = safeProgress(history.getProgressJson());
        map.put("metricsSnapshot", safeMetrics(metricsJson));
        map.put("progress", progressEntries);
        Map<String, Object> metricsMap = ChunkTelemetryUtil.readMetricsMap(metricsJson);
        Map<String, Object> guardrails = buildGuardrailsTelemetry(progressEntries, metricsMap);
        if (!guardrails.isEmpty()) {
            map.put("guardrails", guardrails);
        }

        long start = history.getReviewStartTime();
        long end = history.getReviewEndTime();
        if (start > 0 && end > start) {
            map.put("durationSeconds", (end - start) / 1000);
        } else {
            map.put("durationSeconds", null);
        }

        boolean hasBlocking = history.getCriticalIssues() > 0 || history.getHighIssues() > 0;
        map.put("hasBlockingIssues", hasBlocking);
        map.put("updateReview", history.isUpdateReview());

        return map;
    }

    private Map<String, Object> toSummary(@Nonnull AIReviewHistory history) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", history.getID());
        map.put("pullRequestId", history.getPullRequestId());
        map.put("projectKey", history.getProjectKey());
        map.put("repositorySlug", history.getRepositorySlug());
        map.put("reviewStatus", history.getReviewStatus());
        map.put("reviewOutcome", history.getReviewOutcome());
        long start = history.getReviewStartTime();
        long end = history.getReviewEndTime();
        map.put("startedAt", start > 0 ? start : null);
        map.put("completedAt", end > 0 ? end : null);
        map.put("durationSeconds", (start > 0 && end > start) ? (end - start) / 1000 : null);
        int totalIssues = history.getTotalIssuesFound();
        map.put("totalIssuesFound", totalIssues);
        map.put("criticalIssues", history.getCriticalIssues());
        map.put("highIssues", history.getHighIssues());
        map.put("hasBlockingIssues", history.getCriticalIssues() > 0 || history.getHighIssues() > 0);
        map.put("updateReview", history.isUpdateReview());
        map.put("summary", buildHistorySummary(history, totalIssues));
        return map;
    }

    private String buildHistorySummary(AIReviewHistory history, int totalIssues) {
        List<String> parts = new ArrayList<>();
        String status = history.getReviewStatus();
        if (status != null && !status.trim().isEmpty()) {
            parts.add(humanizeStatus(status));
        }
        long end = history.getReviewEndTime();
        long start = history.getReviewStartTime();
        if (end > 0) {
            parts.add("Finished " + formatTimestamp(end));
        } else if (start > 0) {
            parts.add("Started " + formatTimestamp(start));
        }
        if (totalIssues > 0) {
            parts.add(totalIssues == 1 ? "1 issue" : totalIssues + " issues");
        } else {
            parts.add("No issues");
        }
        if (history.isUpdateReview()) {
            parts.add("Re-review");
        }
        return String.join(" · ", parts);
    }

    private String humanizeStatus(String status) {
        String normalized = status.replace('_', ' ').replace('-', ' ').trim();
        if (normalized.isEmpty()) {
            return "Completed";
        }
        String[] tokens = normalized.split("\\s+");
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < tokens.length; i++) {
            if (tokens[i].isEmpty()) {
                continue;
            }
            if (i > 0) {
                builder.append(' ');
            }
            builder.append(Character.toUpperCase(tokens[i].charAt(0)));
            if (tokens[i].length() > 1) {
                builder.append(tokens[i].substring(1).toLowerCase());
            }
        }
        return builder.toString();
    }

    private String formatTimestamp(long epochMillis) {
        try {
            return Instant.ofEpochMilli(epochMillis)
                    .atZone(zoneId)
                    .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        } catch (Exception ex) {
            return Long.toString(epochMillis);
        }
    }

    private String safeMetrics(String metricsJson) {
        if (metricsJson == null) {
            return "";
        }
        return metricsJson.length() > 10_000
                ? metricsJson.substring(0, 10_000)
                : metricsJson;
    }

    private List<Map<String, Object>> safeProgress(String progressJson) {
        if (progressJson == null || progressJson.trim().isEmpty()) {
            return Collections.emptyList();
        }
        try {
            return PROGRESS_MAPPER.readValue(progressJson, PROGRESS_TYPE);
        } catch (Exception ex) {
            log.debug("Failed to parse progress JSON: {}", ex.getMessage());
            return Collections.emptyList();
        }
    }

    @Nonnull
    Map<String, Object> buildGuardrailsTelemetry(@Nonnull List<Map<String, Object>> progressEntries,
                                                 @Nonnull Map<String, Object> metricsMap) {
        Map<String, Object> telemetry = new LinkedHashMap<>();
        Map<String, Object> limiter = buildLimiterTelemetry(progressEntries, metricsMap);
        if (!limiter.isEmpty()) {
            telemetry.put("limiter", limiter);
        }
        Map<String, Object> circuit = buildCircuitTelemetry(progressEntries, metricsMap);
        if (!circuit.isEmpty()) {
            telemetry.put("circuit", circuit);
        }
        return telemetry.isEmpty() ? Collections.emptyMap() : telemetry;
    }

    @Nonnull
    private Map<String, Object> buildLimiterTelemetry(@Nonnull List<Map<String, Object>> progressEntries,
                                                      @Nonnull Map<String, Object> metricsMap) {
        List<Map<String, Object>> incidents = new ArrayList<>();
        for (Map<String, Object> event : progressEntries) {
            String stage = asString(event.get("stage"));
            if (!"review.throttled".equals(stage)) {
                continue;
            }
            Map<String, Object> details = asNestedMap(event.get("details"));
            if (details.isEmpty()) {
                continue;
            }
            Map<String, Object> incident = new LinkedHashMap<>();
            long timestamp = asLong(event.get("timestamp"), 0);
            if (timestamp > 0) {
                incident.put("timestamp", timestamp);
            }
            putIfPresent(incident, "scope", asString(details.get("scope")));
            putIfPresent(incident, "identifier", asString(details.get("identifier")));
            long retryAfterMs = asLong(details.get("retryAfterMs"), -1);
            if (retryAfterMs >= 0) {
                incident.put("retryAfterMs", retryAfterMs);
            }
            Map<String, Object> snapshot = normalizeLimiterSnapshot(asNestedMap(details.get("limiterSnapshot")));
            if (!snapshot.isEmpty()) {
                incident.put("snapshot", snapshot);
            }
            incidents.add(incident);
        }

        Map<String, Object> telemetry = new LinkedHashMap<>();
        if (!incidents.isEmpty()) {
            telemetry.put("incidents", incidents);
        }

        Map<String, Object> snapshot = normalizeLimiterSnapshot(asNestedMap(metricsMap.get("rate.snapshot")));
        if (snapshot.isEmpty()) {
            snapshot = buildLimiterSnapshotFromMetrics(metricsMap);
        }
        if (!snapshot.isEmpty()) {
            telemetry.put("snapshot", snapshot);
        }

        String scope = asString(metricsMap.get("rate.scope"));
        if (scope == null) {
            scope = asString(snapshot.get("scope"));
        }
        if (scope != null) {
            telemetry.put("scope", scope);
        }
        String identifier = asString(metricsMap.get("rate.identifier"));
        if (identifier == null) {
            identifier = asString(snapshot.get("identifier"));
        }
        if (identifier != null) {
            telemetry.put("identifier", identifier);
        }
        long retryAfter = asLong(metricsMap.get("rate.retryAfterMs"), -1);
        if (retryAfter >= 0) {
            telemetry.put("retryAfterMs", retryAfter);
        } else {
            long snapshotReset = asLong(snapshot.get("resetInMs"), -1);
            if (snapshotReset >= 0) {
                telemetry.put("retryAfterMs", snapshotReset);
            }
        }

        return telemetry.isEmpty() ? Collections.emptyMap() : telemetry;
    }

    @Nonnull
    private Map<String, Object> buildCircuitTelemetry(@Nonnull List<Map<String, Object>> progressEntries,
                                                      @Nonnull Map<String, Object> metricsMap) {
        List<Map<String, Object>> samples = new ArrayList<>();
        for (Map<String, Object> event : progressEntries) {
            Map<String, Object> details = asNestedMap(event.get("details"));
            if (details.isEmpty()) {
                continue;
            }
            Map<String, Object> snapshot = normalizeCircuitSnapshot(asNestedMap(details.get("circuitSnapshot")));
            if (snapshot.isEmpty()) {
                continue;
            }
            Map<String, Object> sample = new LinkedHashMap<>();
            long timestamp = asLong(event.get("timestamp"), 0);
            if (timestamp > 0) {
                sample.put("timestamp", timestamp);
            }
            String stage = asString(event.get("stage"));
            if (stage != null) {
                sample.put("stage", stage);
            }
            sample.put("snapshot", snapshot);
            String state = asString(details.get("circuitState"));
            if (state != null) {
                sample.put("state", state);
            } else if (snapshot.containsKey("state")) {
                sample.put("state", snapshot.get("state"));
            }
            samples.add(sample);
        }

        Map<String, Object> telemetry = new LinkedHashMap<>();
        if (!samples.isEmpty()) {
            telemetry.put("samples", samples);
        }

        Map<String, Object> latest = normalizeCircuitSnapshot(asNestedMap(metricsMap.get("ai.model.circuit.snapshot")));
        if (latest.isEmpty() && !samples.isEmpty()) {
            Object fallback = samples.get(samples.size() - 1).get("snapshot");
            if (fallback instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> copied = new LinkedHashMap<>((Map<String, Object>) fallback);
                latest = copied;
            }
        }
        if (!latest.isEmpty()) {
            telemetry.put("latest", latest);
        }

        return telemetry.isEmpty() ? Collections.emptyMap() : telemetry;
    }

    @Nonnull
    private Map<String, Object> normalizeLimiterSnapshot(@Nonnull Map<String, Object> raw) {
        if (raw.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, Object> snapshot = new LinkedHashMap<>();
        putIfPresent(snapshot, "scope", asString(raw.get("scope")));
        putIfPresent(snapshot, "identifier", asString(raw.get("identifier")));
        putLongIfPresent(snapshot, "limitPerHour", raw.get("limitPerHour"));
        putLongIfPresent(snapshot, "consumed", raw.get("consumed"));
        putLongIfPresent(snapshot, "remaining", raw.get("remaining"));
        putLongIfPresent(snapshot, "windowStart", raw.get("windowStart"));
        putLongIfPresent(snapshot, "updatedAt", raw.get("updatedAt"));
        putLongIfPresent(snapshot, "resetInMs", raw.get("resetInMs"));
        return snapshot.isEmpty() ? Collections.emptyMap() : snapshot;
    }

    @Nonnull
    private Map<String, Object> buildLimiterSnapshotFromMetrics(@Nonnull Map<String, Object> metricsMap) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        putIfPresent(snapshot, "scope", asString(metricsMap.get("rate.scope")));
        putIfPresent(snapshot, "identifier", asString(metricsMap.get("rate.identifier")));
        putLongIfPresent(snapshot, "limitPerHour", metricsMap.get("rate.limitPerHour"));
        putLongIfPresent(snapshot, "remaining", metricsMap.get("rate.remaining"));
        putLongIfPresent(snapshot, "consumed", metricsMap.get("rate.consumed"));
        putLongIfPresent(snapshot, "retryAfterMs", metricsMap.get("rate.retryAfterMs"));
        return snapshot.isEmpty() ? Collections.emptyMap() : snapshot;
    }

    @Nonnull
    private Map<String, Object> normalizeCircuitSnapshot(@Nonnull Map<String, Object> raw) {
        if (raw.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, Object> snapshot = new LinkedHashMap<>();
        putIfPresent(snapshot, "state", asString(raw.get("state")));
        putLongIfPresent(snapshot, "failureCount", raw.get("failureCount"));
        putLongIfPresent(snapshot, "openEvents", raw.get("openEvents"));
        putLongIfPresent(snapshot, "blockedCalls", raw.get("blockedCalls"));
        putLongIfPresent(snapshot, "succeededCalls", raw.get("succeededCalls"));
        putLongIfPresent(snapshot, "failedCalls", raw.get("failedCalls"));
        putLongIfPresent(snapshot, "clientBlockedCalls", raw.get("clientBlockedCalls"));
        putLongIfPresent(snapshot, "clientHardFailures", raw.get("clientHardFailures"));
        return snapshot.isEmpty() ? Collections.emptyMap() : snapshot;
    }

    @Nonnull
    @SuppressWarnings("unchecked")
    private Map<String, Object> asNestedMap(@Nullable Object value) {
        if (!(value instanceof Map)) {
            return Collections.emptyMap();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        ((Map<?, ?>) value).forEach((k, v) -> {
            if (k instanceof String && v != null) {
                result.put((String) k, v);
            }
        });
        return result;
    }

    @Nullable
    private String asString(@Nullable Object value) {
        if (value == null) {
            return null;
        }
        String str = value.toString().trim();
        return str.isEmpty() ? null : str;
    }

    private void putIfPresent(@Nonnull Map<String, Object> target,
                              @Nonnull String key,
                              @Nullable Object value) {
        if (value != null) {
            target.put(key, value);
        }
    }

    private void putLongIfPresent(@Nonnull Map<String, Object> target,
                                  @Nonnull String key,
                                  @Nullable Object raw) {
        long value = asLong(raw, Long.MIN_VALUE);
        if (value != Long.MIN_VALUE) {
            target.put(key, value);
        }
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
        map.put("requestBytes", chunk.getRequestBytes());
        map.put("responseBytes", chunk.getResponseBytes());
        map.put("statusCode", chunk.getStatusCode());
        map.put("timeout", chunk.isTimeout());
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

    private Map<String, Object> buildIoTotals(long requestBytes,
                                              long responseBytes,
                                              long chunkRecords,
                                              long timeoutCount,
                                              Map<Integer, Long> statusCounts) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("requestBytes", requestBytes);
        map.put("responseBytes", responseBytes);
        map.put("chunkCount", chunkRecords);
        map.put("timeoutCount", timeoutCount);
        map.put("avgRequestBytes", chunkRecords > 0 ? (double) requestBytes / chunkRecords : 0d);
        map.put("avgResponseBytes", chunkRecords > 0 ? (double) responseBytes / chunkRecords : 0d);
        map.put("statusCounts", statusCounts);
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

    public DurationStats getRecentDurationStats(int sampleSize) {
        final int limit = Math.min(Math.max(sampleSize, 10), 500);
        return ao.executeInTransaction(() -> {
            Query query = Query.select()
                    .where("REVIEW_END_TIME > 0")
                    .order("REVIEW_END_TIME DESC")
                    .limit(limit);
            AIReviewHistory[] histories = ao.find(AIReviewHistory.class, query);
            if (histories.length == 0) {
                return DurationStats.empty();
            }
            RunningStats global = new RunningStats();
            Map<String, RunningStats> repoStats = new HashMap<>();
            Map<String, RunningStats> projectStats = new HashMap<>();

            for (AIReviewHistory history : histories) {
                long start = history.getReviewStartTime();
                long end = history.getReviewEndTime();
                if (start <= 0 || end <= 0 || end <= start) {
                    continue;
                }
                long duration = end - start;
                global.add(duration);
                String repoKey = repoKey(history.getProjectKey(), history.getRepositorySlug());
                if (repoKey != null) {
                    repoStats.computeIfAbsent(repoKey, key -> new RunningStats()).add(duration);
                }
                String projectKey = normalizeKey(history.getProjectKey());
                if (projectKey != null) {
                    projectStats.computeIfAbsent(projectKey, key -> new RunningStats()).add(duration);
                }
            }
            if (global.getSamples() == 0) {
                return DurationStats.empty();
            }
            return new DurationStats(
                    global.getAverage(),
                    toAverageMap(repoStats),
                    toAverageMap(projectStats),
                    global.getSamples());
        });
    }

    public ModelStats getRecentModelStats(int sampleLimit) {
        final int limit = Math.min(Math.max(sampleLimit, 50), 2000);
        return ao.executeInTransaction(() -> {
            AIReviewChunk[] chunks = ao.find(AIReviewChunk.class,
                    Query.select()
                            .order("ID DESC")
                            .limit(limit));
            if (chunks.length == 0) {
                return ModelStats.empty();
            }
            List<ModelSample> samples = new ArrayList<>(chunks.length);
            for (AIReviewChunk chunk : chunks) {
                samples.add(ModelSample.from(chunk,
                        normalizeEndpoint(chunk.getEndpoint()),
                        normalizeModel(chunk.getModel()),
                        chunk.getID()));
            }
            return aggregateModelStats(samples, chunks.length, System.currentTimeMillis());
        });
    }

    public Map<String, Object> getRetentionStats(int retentionDays) {
        final int days = Math.max(1, retentionDays);
        final long now = System.currentTimeMillis();
        final long cutoff = now - TimeUnit.DAYS.toMillis(days);
        return ao.executeInTransaction(() -> {
            int total = ao.count(AIReviewHistory.class);
            int older = ao.count(AIReviewHistory.class,
                    Query.select().where("REVIEW_START_TIME < ?", cutoff));
            long oldestStart = 0L;
            long newestStart = 0L;
            AIReviewHistory[] oldest = ao.find(AIReviewHistory.class,
                    Query.select().order("REVIEW_START_TIME ASC").limit(1));
            AIReviewHistory[] newest = ao.find(AIReviewHistory.class,
                    Query.select().order("REVIEW_START_TIME DESC").limit(1));
            if (oldest.length > 0) {
                oldestStart = oldest[0].getReviewStartTime();
            }
            if (newest.length > 0) {
                newestStart = newest[0].getReviewStartTime();
            }
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("retentionDays", days);
            map.put("totalEntries", total);
            map.put("entriesOlderThanRetention", older);
            map.put("cutoffEpochMs", cutoff);
            map.put("oldestReviewStart", oldestStart);
            map.put("newestReviewStart", newestStart);
            map.put("generatedAt", now);
            return map;
        });
    }

    public List<Map<String, Object>> exportRetentionCandidates(int retentionDays, int limit) {
        RetentionExportBatch batch = buildRetentionExport(retentionDays, limit, false);
        if (batch.getEntries().isEmpty()) {
            return Collections.emptyList();
        }
        return batch.getEntries().stream()
                .map(entry -> entry.toMap(false))
                .collect(Collectors.toList());
    }

    public RetentionExportBatch buildRetentionExport(int retentionDays, int limit, boolean includeChunks) {
        final int days = Math.max(1, retentionDays);
        final int rowLimit = limit <= 0 ? 100 : limit;
        final long now = System.currentTimeMillis();
        final long cutoff = now - TimeUnit.DAYS.toMillis(days);
        return ao.executeInTransaction(() -> {
            AIReviewHistory[] histories = ao.find(AIReviewHistory.class,
                    Query.select()
                            .where("REVIEW_START_TIME < ?", cutoff)
                            .order("REVIEW_START_TIME ASC")
                            .limit(rowLimit));
            if (histories.length == 0) {
                return new RetentionExportBatch(days, rowLimit, cutoff, now, Collections.emptyList());
            }
            List<RetentionExportEntry> entries = new ArrayList<>(histories.length);
            for (AIReviewHistory history : histories) {
                entries.add(toRetentionExportEntry(history, includeChunks));
            }
            return new RetentionExportBatch(days, rowLimit, cutoff, now, entries);
        });
    }

    public Map<String, Object> checkRetentionIntegrity(int retentionDays, int sampleLimit) {
        return runRetentionIntegrityCheck(retentionDays, sampleLimit, false).toMap();
    }

    public RetentionIntegrityReport runRetentionIntegrityCheck(int retentionDays,
                                                               int sampleLimit,
                                                               boolean repair) {
        final int days = Math.max(1, retentionDays);
        final int limit = sampleLimit <= 0 ? 100 : sampleLimit;
        final long now = System.currentTimeMillis();
        final long cutoff = now - TimeUnit.DAYS.toMillis(days);
        return ao.executeInTransaction(() -> {
            int totalCandidates = ao.count(AIReviewHistory.class,
                    Query.select().where("REVIEW_START_TIME < ?", cutoff));
            AIReviewHistory[] histories = ao.find(AIReviewHistory.class,
                    Query.select()
                            .where("REVIEW_START_TIME < ?", cutoff)
                            .order("REVIEW_START_TIME ASC")
                            .limit(limit));
            List<RetentionIntegrityReport.Mismatch> mismatches = new ArrayList<>();
            List<RetentionIntegrityReport.JsonIssue> progressIssues = new ArrayList<>();
            List<RetentionIntegrityReport.JsonIssue> metricIssues = new ArrayList<>();
            List<RetentionIntegrityReport.RepairAction> repairs = new ArrayList<>();
            int mismatchCount = 0;
            int progressCount = 0;
            int metricCount = 0;
            int repairsApplied = 0;
            final int sampleLimitPerList = 50;

            for (AIReviewHistory history : histories) {
                boolean dirty = false;
                List<String> actions = new ArrayList<>();
                int expected = Math.max(0, history.getTotalChunks());
                int actual = history.getChunks().length;
                if (expected != actual) {
                    mismatchCount++;
                    if (mismatches.size() < sampleLimitPerList) {
                        mismatches.add(new RetentionIntegrityReport.Mismatch(
                                history.getID(),
                                history.getProjectKey(),
                                history.getRepositorySlug(),
                                history.getPullRequestId(),
                                expected,
                                actual,
                                history.getReviewStartTime()));
                    }
                    if (repair) {
                        history.setTotalChunks(actual);
                        dirty = true;
                        actions.add("totalChunks=" + actual);
                    }
                }

                JsonStatus progressStatus = validateJson(history.getProgressJson());
                if (!progressStatus.valid) {
                    progressCount++;
                    if (progressIssues.size() < sampleLimitPerList) {
                        progressIssues.add(new RetentionIntegrityReport.JsonIssue(
                                history.getID(),
                                "progressJson",
                                progressStatus.message));
                    }
                    if (repair && history.getProgressJson() != null) {
                        history.setProgressJson(null);
                        dirty = true;
                        actions.add("progressJsonCleared");
                    }
                }

                JsonStatus metricsStatus = validateJson(history.getMetricsJson());
                if (!metricsStatus.valid) {
                    metricCount++;
                    if (metricIssues.size() < sampleLimitPerList) {
                        metricIssues.add(new RetentionIntegrityReport.JsonIssue(
                                history.getID(),
                                "metricsJson",
                                metricsStatus.message));
                    }
                    if (repair && history.getMetricsJson() != null) {
                        history.setMetricsJson(null);
                        dirty = true;
                        actions.add("metricsJsonCleared");
                    }
                }

                if (dirty) {
                    history.save();
                    repairsApplied++;
                    if (!actions.isEmpty() && repairs.size() < sampleLimitPerList) {
                        repairs.add(new RetentionIntegrityReport.RepairAction(
                                history.getID(),
                                history.getProjectKey(),
                                history.getRepositorySlug(),
                                history.getPullRequestId(),
                                actions));
                    }
                }
            }

            return new RetentionIntegrityReport(
                    days,
                    cutoff,
                    now,
                    totalCandidates,
                    histories.length,
                    mismatchCount,
                    progressCount,
                    metricCount,
                    repairsApplied,
                    mismatches,
                    progressIssues,
                    metricIssues,
                    repairs,
                    repair);
        });
    }

    private RetentionExportEntry toRetentionExportEntry(AIReviewHistory history, boolean includeChunks) {
        List<RetentionChunkExport> chunks = includeChunks
                ? Arrays.stream(history.getChunks())
                .map(this::toRetentionChunkExport)
                .collect(Collectors.toList())
                : Collections.emptyList();
        String commitId = limitString(history.getCommitId(), 255);
        String fromCommit = limitString(history.getFromCommit(), 40);
        String toCommit = limitString(history.getToCommit(), 40);
        return new RetentionExportEntry(
                history.getID(),
                history.getProjectKey(),
                history.getRepositorySlug(),
                history.getPullRequestId(),
                history.getReviewStartTime(),
                history.getReviewEndTime(),
                history.getReviewStatus(),
                history.getReviewOutcome(),
                history.getModelUsed(),
                history.getProfileKey(),
                history.getSummaryCommentId(),
                history.isAutoApproveEnabled(),
                history.getTotalChunks(),
                chunks.size(),
                history.getTotalIssuesFound(),
                history.getCriticalIssues(),
                history.getHighIssues(),
                history.getMediumIssues(),
                history.getLowIssues(),
                resolveDurationSeconds(history),
                commitId,
                fromCommit,
                toCommit,
                history.getPrimaryModelInvocations(),
                history.getPrimaryModelSuccesses(),
                history.getPrimaryModelFailures(),
                history.getFallbackModelInvocations(),
                history.getFallbackModelSuccesses(),
                history.getFallbackModelFailures(),
                history.getDiffSize(),
                history.getLineCount(),
                chunks);
    }

    private RetentionChunkExport toRetentionChunkExport(AIReviewChunk chunk) {
        return new RetentionChunkExport(
                chunk.getChunkId(),
                chunk.getSequence(),
                chunk.getModel(),
                chunk.getEndpoint(),
                chunk.getRole(),
                chunk.isSuccess(),
                chunk.getAttempts(),
                chunk.getRetries(),
                chunk.getDurationMs(),
                chunk.isTimeout(),
                chunk.getStatusCode(),
                chunk.isModelNotFound(),
                chunk.getRequestBytes(),
                chunk.getResponseBytes(),
                truncate(chunk.getLastError(), 1024));
    }

    private JsonStatus validateJson(String payload) {
        if (payload == null) {
            return JsonStatus.valid();
        }
        String trimmed = payload.trim();
        if (trimmed.isEmpty()) {
            return JsonStatus.valid();
        }
        try {
            PROGRESS_MAPPER.readTree(trimmed);
            return JsonStatus.valid();
        } catch (Exception ex) {
            return JsonStatus.invalid(truncate(ex.getMessage(), 200));
        }
    }

    private Map<String, Double> toAverageMap(Map<String, RunningStats> stats) {
        if (stats.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, Double> averages = new HashMap<>();
        stats.forEach((key, value) -> {
            double avg = value.getAverage();
            if (avg > 0) {
                averages.put(key, avg);
            }
        });
        return Collections.unmodifiableMap(averages);
    }

    private String repoKey(String projectKey, String repositorySlug) {
        String project = normalizeKey(projectKey);
        String repo = normalizeKey(repositorySlug);
        if (project == null || repo == null) {
            return null;
        }
        return project + "/" + repo;
    }

    private String normalizeKey(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        return trimmed.toLowerCase(Locale.ROOT);
    }

    private static final class RunningStats {
        private long total;
        private int samples;

        void add(long value) {
            if (value <= 0) {
                return;
            }
            total += value;
            samples++;
        }

        double getAverage() {
            return samples == 0 ? 0D : (double) total / samples;
        }

        int getSamples() {
            return samples;
        }
    }

    public static final class DurationStats {
        private final double globalAverageMs;
        private final Map<String, Double> repoAverageMs;
        private final Map<String, Double> projectAverageMs;
        private final int samples;

        DurationStats(double globalAverageMs,
                      Map<String, Double> repoAverageMs,
                      Map<String, Double> projectAverageMs,
                      int samples) {
            this.globalAverageMs = globalAverageMs;
            this.repoAverageMs = repoAverageMs != null ? repoAverageMs : Collections.emptyMap();
            this.projectAverageMs = projectAverageMs != null ? projectAverageMs : Collections.emptyMap();
            this.samples = samples;
        }

        public double estimate(String projectKey, String repositorySlug, double fallbackMs) {
            String repoKey = key(projectKey, repositorySlug);
            if (repoKey != null) {
                Double repoAvg = repoAverageMs.get(repoKey);
                if (repoAvg != null && repoAvg > 0) {
                    return repoAvg;
                }
            }
            String project = key(projectKey, null);
            if (project != null) {
                Double projectAvg = projectAverageMs.get(project);
                if (projectAvg != null && projectAvg > 0) {
                    return projectAvg;
                }
            }
            if (globalAverageMs > 0) {
                return globalAverageMs;
            }
            return fallbackMs;
        }

        public int getSamples() {
            return samples;
        }

        public static DurationStats empty() {
            return new DurationStats(0D, Collections.emptyMap(), Collections.emptyMap(), 0);
        }

        private String key(String projectKey, String repositorySlug) {
            if (projectKey == null) {
                return null;
            }
            String project = projectKey.trim().toLowerCase(Locale.ROOT);
            if (repositorySlug == null) {
                return project;
            }
            String repo = repositorySlug.trim().toLowerCase(Locale.ROOT);
            if (repo.isEmpty()) {
                return project;
            }
            return project + "/" + repo;
        }
    }

    static ModelStats aggregateModelStats(List<ModelSample> samples, int scanned, long generatedAt) {
        if (samples == null || samples.isEmpty()) {
            return ModelStats.empty();
        }
        Map<ModelEndpointKey, ModelAggregation> aggregations = new LinkedHashMap<>();
        for (ModelSample sample : samples) {
            if (sample == null || sample.model == null || sample.model.isEmpty()) {
                continue;
            }
            ModelEndpointKey key = new ModelEndpointKey(sample.endpoint, sample.model);
            aggregations.computeIfAbsent(key, ModelAggregation::new).accept(sample);
        }
        if (aggregations.isEmpty()) {
            return ModelStats.empty();
        }
        List<ModelStats.Entry> entries = aggregations.values().stream()
                .map(ModelAggregation::toEntry)
                .sorted(Comparator.comparingLong(ModelStats.Entry::getTotalInvocations).reversed())
                .collect(Collectors.toList());
        return new ModelStats(entries, scanned, generatedAt);
    }

    public static final class ModelStats {
        private final List<Entry> entries;
        private final int scannedSamples;
        private final long generatedAt;

        ModelStats(List<Entry> entries, int scannedSamples, long generatedAt) {
            this.entries = entries != null ? entries : Collections.emptyList();
            this.scannedSamples = scannedSamples;
            this.generatedAt = generatedAt;
        }

        public static ModelStats empty() {
            return new ModelStats(Collections.emptyList(), 0, System.currentTimeMillis());
        }

        public List<Entry> getEntries() {
            return entries;
        }

        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("generatedAt", generatedAt);
            map.put("scannedSamples", scannedSamples);
            List<Map<String, Object>> entryMaps = entries.stream()
                    .map(Entry::toMap)
                    .collect(Collectors.toList());
            map.put("entries", entryMaps);
            return map;
        }

        public static final class Entry {
            private final String endpoint;
            private final String model;
            private final long totalInvocations;
            private final double averageDurationMs;
            private final double percentile95thMs;
            private final double successRate;
            private final long failureCount;
            private final long timeoutCount;
            private final String lastError;
            private final Map<String, Long> statusCounts;

            Entry(String endpoint,
                  String model,
                  long totalInvocations,
                  double averageDurationMs,
                  double percentile95thMs,
                  double successRate,
                  long failureCount,
                  long timeoutCount,
                  String lastError,
                  Map<String, Long> statusCounts) {
                this.endpoint = endpoint;
                this.model = model;
                this.totalInvocations = totalInvocations;
                this.averageDurationMs = averageDurationMs;
                this.percentile95thMs = percentile95thMs;
                this.successRate = successRate;
                this.failureCount = failureCount;
                this.timeoutCount = timeoutCount;
                this.lastError = lastError;
                this.statusCounts = statusCounts != null ? statusCounts : Collections.emptyMap();
            }

            public long getTotalInvocations() {
                return totalInvocations;
            }

            public double getSuccessRate() {
                return successRate;
            }

            public long getTimeoutCount() {
                return timeoutCount;
            }

            public Map<String, Long> getStatusCounts() {
                return statusCounts;
            }

            public Map<String, Object> toMap() {
                Map<String, Object> map = new LinkedHashMap<>();
                map.put("endpoint", endpoint);
                map.put("model", model);
                map.put("totalInvocations", totalInvocations);
                map.put("averageDurationMs", averageDurationMs);
                map.put("p95DurationMs", percentile95thMs);
                map.put("successRate", successRate);
                map.put("failureCount", failureCount);
                map.put("timeoutCount", timeoutCount);
                if (!statusCounts.isEmpty()) {
                    map.put("statusCounts", statusCounts);
                }
                if (lastError != null && !lastError.isEmpty()) {
                    map.put("lastError", lastError);
                }
                return map;
            }
        }
    }

    static final class ModelSample {
        final String endpoint;
        final String model;
        final long durationMs;
        final boolean success;
        final boolean timeout;
        final int statusCode;
        final String lastError;
        final long chunkId;

        private ModelSample(String endpoint,
                            String model,
                            long durationMs,
                            boolean success,
                            boolean timeout,
                            int statusCode,
                            String lastError,
                            long chunkId) {
            this.endpoint = endpoint;
            this.model = model;
            this.durationMs = durationMs;
            this.success = success;
            this.timeout = timeout;
            this.statusCode = statusCode;
            this.lastError = lastError;
            this.chunkId = chunkId;
        }

        static ModelSample from(AIReviewChunk chunk,
                                String endpoint,
                                String model,
                                long chunkId) {
            long duration = Math.max(0, chunk.getDurationMs());
            String error = chunk.getLastError();
            if (error != null && error.length() > 512) {
                error = error.substring(0, 512);
            }
            return new ModelSample(endpoint,
                    model,
                    duration,
                    chunk.isSuccess(),
                    chunk.isTimeout(),
                    chunk.getStatusCode(),
                    error,
                    chunkId);
        }

        static ModelSample of(String endpoint,
                              String model,
                              long durationMs,
                              boolean success,
                              boolean timeout,
                              int statusCode,
                              String lastError,
                              long chunkId) {
            return new ModelSample(endpoint, model, durationMs, success, timeout, statusCode, lastError, chunkId);
        }
    }

    private static final class ModelEndpointKey {
        private final String endpoint;
        private final String model;

        private ModelEndpointKey(String endpoint, String model) {
            this.endpoint = endpoint;
            this.model = model;
        }

        private ModelEndpointKey(ModelSample sample) {
            this(sample.endpoint, sample.model);
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (!(obj instanceof ModelEndpointKey)) {
                return false;
            }
            ModelEndpointKey other = (ModelEndpointKey) obj;
            return Objects.equals(endpoint, other.endpoint) && Objects.equals(model, other.model);
        }

        @Override
        public int hashCode() {
            return Objects.hash(endpoint, model);
        }
    }

    private static final class ModelAggregation {
        private static final int DURATION_SAMPLE_LIMIT = 200;
        private final String endpoint;
        private final String model;
        private final List<Long> durationSamples = new ArrayList<>();
        private final Map<Integer, Long> statusCounts = new LinkedHashMap<>();
        private long totalDuration;
        private long invocations;
        private long successes;
        private long failures;
        private long timeouts;
        private long lastErrorChunkId = -1;
        private String lastError;

        private ModelAggregation(ModelEndpointKey key) {
            this.endpoint = key.endpoint;
            this.model = key.model;
        }

        void accept(ModelSample sample) {
            invocations++;
            totalDuration += sample.durationMs;
            if (durationSamples.size() < DURATION_SAMPLE_LIMIT) {
                durationSamples.add(sample.durationMs);
            }
            if (sample.success) {
                successes++;
            } else {
                failures++;
                if (sample.lastError != null && (lastError == null || sample.chunkId > lastErrorChunkId)) {
                    lastErrorChunkId = sample.chunkId;
                    lastError = sample.lastError;
                }
            }
            if (sample.timeout) {
                timeouts++;
            }
            if (sample.statusCode > 0) {
                statusCounts.merge(sample.statusCode, 1L, Long::sum);
            }
        }

        ModelStats.Entry toEntry() {
            double avg = invocations == 0 ? 0d : (double) totalDuration / invocations;
            double p95 = durationSamples.isEmpty() ? 0d : percentileLong(durationSamples, 0.95d);
            double successRate = invocations == 0 ? 0d : (double) successes / invocations;
            Map<String, Long> statusMap = new LinkedHashMap<>();
            statusCounts.forEach((code, count) -> statusMap.put(String.valueOf(code), count));
            return new ModelStats.Entry(
                    endpoint,
                    model,
                    invocations,
                    avg,
                    p95,
                    successRate,
                    failures,
                    timeouts,
                    lastError,
                    Collections.unmodifiableMap(statusMap));
        }

        private double percentileLong(List<Long> values, double percentile) {
            if (values.isEmpty()) {
                return 0d;
            }
            List<Long> sorted = new ArrayList<>(values);
            Collections.sort(sorted);
            if (sorted.size() == 1) {
                return sorted.get(0);
            }
            double index = percentile * (sorted.size() - 1);
            int lower = (int) Math.floor(index);
            int upper = (int) Math.ceil(index);
            if (lower == upper) {
                return sorted.get(lower);
            }
            double fraction = index - lower;
            double lowerValue = sorted.get(lower);
            double upperValue = sorted.get(upper);
            return lowerValue + (upperValue - lowerValue) * fraction;
        }
    }

    public static final class RetentionExportBatch {
        private final int retentionDays;
        private final int limit;
        private final long cutoffEpochMs;
        private final long generatedAt;
        private final List<RetentionExportEntry> entries;

        public RetentionExportBatch(int retentionDays,
                             int limit,
                             long cutoffEpochMs,
                             long generatedAt,
                             List<RetentionExportEntry> entries) {
            this.retentionDays = retentionDays;
            this.limit = limit;
            this.cutoffEpochMs = cutoffEpochMs;
            this.generatedAt = generatedAt;
            this.entries = entries != null ? entries : Collections.emptyList();
        }

        public int getRetentionDays() {
            return retentionDays;
        }

        public int getLimit() {
            return limit;
        }

        public long getCutoffEpochMs() {
            return cutoffEpochMs;
        }

        public long getGeneratedAt() {
            return generatedAt;
        }

        public List<RetentionExportEntry> getEntries() {
            return entries;
        }

        public Map<String, Object> toMap(boolean includeChunks) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("retentionDays", retentionDays);
            map.put("limit", limit);
            map.put("generatedAt", generatedAt);
            map.put("cutoffEpochMs", cutoffEpochMs);
            map.put("count", entries.size());
            map.put("entries", entries.stream()
                    .map(entry -> entry.toMap(includeChunks))
                    .collect(Collectors.toList()));
            return map;
        }
    }

    public static final class RetentionExportEntry {
        private final long historyId;
        private final String projectKey;
        private final String repositorySlug;
        private final long pullRequestId;
        private final long reviewStartTime;
        private final long reviewEndTime;
        private final String reviewStatus;
        private final String reviewOutcome;
        private final String modelUsed;
        private final String profileKey;
        private final long summaryCommentId;
        private final boolean autoApproveEnabled;
        private final int totalChunksRecorded;
        private final int actualChunks;
        private final int totalIssues;
        private final int criticalIssues;
        private final int highIssues;
        private final int mediumIssues;
        private final int lowIssues;
        private final double analysisTimeSeconds;
        private final String commitId;
        private final String fromCommit;
        private final String toCommit;
        private final int primaryInvocations;
        private final int primarySuccesses;
        private final int primaryFailures;
        private final int fallbackInvocations;
        private final int fallbackSuccesses;
        private final int fallbackFailures;
        private final long diffSize;
        private final int lineCount;
        private final List<RetentionChunkExport> chunks;

        public RetentionExportEntry(long historyId,
                             String projectKey,
                             String repositorySlug,
                             long pullRequestId,
                             long reviewStartTime,
                             long reviewEndTime,
                             String reviewStatus,
                             String reviewOutcome,
                             String modelUsed,
                             String profileKey,
                             long summaryCommentId,
                             boolean autoApproveEnabled,
                             int totalChunksRecorded,
                             int actualChunks,
                             int totalIssues,
                             int criticalIssues,
                             int highIssues,
                             int mediumIssues,
                             int lowIssues,
                             double analysisTimeSeconds,
                             String commitId,
                             String fromCommit,
                             String toCommit,
                             int primaryInvocations,
                             int primarySuccesses,
                             int primaryFailures,
                             int fallbackInvocations,
                             int fallbackSuccesses,
                             int fallbackFailures,
                             long diffSize,
                             int lineCount,
                             List<RetentionChunkExport> chunks) {
            this.historyId = historyId;
            this.projectKey = projectKey;
            this.repositorySlug = repositorySlug;
            this.pullRequestId = pullRequestId;
            this.reviewStartTime = reviewStartTime;
            this.reviewEndTime = reviewEndTime;
            this.reviewStatus = reviewStatus;
            this.reviewOutcome = reviewOutcome;
            this.modelUsed = modelUsed;
            this.profileKey = profileKey;
            this.summaryCommentId = summaryCommentId;
            this.autoApproveEnabled = autoApproveEnabled;
            this.totalChunksRecorded = totalChunksRecorded;
            this.actualChunks = actualChunks;
            this.totalIssues = totalIssues;
            this.criticalIssues = criticalIssues;
            this.highIssues = highIssues;
            this.mediumIssues = mediumIssues;
            this.lowIssues = lowIssues;
            this.analysisTimeSeconds = analysisTimeSeconds;
            this.commitId = commitId;
            this.fromCommit = fromCommit;
            this.toCommit = toCommit;
            this.primaryInvocations = primaryInvocations;
            this.primarySuccesses = primarySuccesses;
            this.primaryFailures = primaryFailures;
            this.fallbackInvocations = fallbackInvocations;
            this.fallbackSuccesses = fallbackSuccesses;
            this.fallbackFailures = fallbackFailures;
            this.diffSize = diffSize;
            this.lineCount = lineCount;
            this.chunks = chunks != null ? chunks : Collections.emptyList();
        }

        public Map<String, Object> toMap(boolean includeChunks) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("historyId", historyId);
            map.put("projectKey", projectKey);
            map.put("repositorySlug", repositorySlug);
            map.put("pullRequestId", pullRequestId);
            map.put("reviewStartTime", reviewStartTime);
            map.put("reviewEndTime", reviewEndTime);
            map.put("reviewStatus", reviewStatus);
            map.put("reviewOutcome", reviewOutcome);
            map.put("modelUsed", modelUsed);
            map.put("profileKey", profileKey);
            map.put("summaryCommentId", summaryCommentId);
            map.put("autoApproveEnabled", autoApproveEnabled);
            map.put("totalChunksRecorded", totalChunksRecorded);
            map.put("actualChunks", actualChunks);
            map.put("totalIssuesFound", totalIssues);
            map.put("criticalIssues", criticalIssues);
            map.put("highIssues", highIssues);
            map.put("mediumIssues", mediumIssues);
            map.put("lowIssues", lowIssues);
            map.put("analysisTimeSeconds", analysisTimeSeconds);
            map.put("commitId", commitId);
            map.put("fromCommit", fromCommit);
            map.put("toCommit", toCommit);
            map.put("primaryModelInvocations", primaryInvocations);
            map.put("primaryModelSuccesses", primarySuccesses);
            map.put("primaryModelFailures", primaryFailures);
            map.put("fallbackModelInvocations", fallbackInvocations);
            map.put("fallbackModelSuccesses", fallbackSuccesses);
            map.put("fallbackModelFailures", fallbackFailures);
            map.put("diffSize", diffSize);
            map.put("lineCount", lineCount);
            if (includeChunks) {
                map.put("chunks", chunks.stream()
                        .map(RetentionChunkExport::toMap)
                        .collect(Collectors.toList()));
            }
            return map;
        }
    }

    public static final class RetentionChunkExport {
        private final String chunkId;
        private final int sequence;
        private final String model;
        private final String endpoint;
        private final String role;
        private final boolean success;
        private final int attempts;
        private final int retries;
        private final long durationMs;
        private final boolean timeout;
        private final int statusCode;
        private final boolean modelNotFound;
        private final long requestBytes;
        private final long responseBytes;
        private final String lastError;

        public RetentionChunkExport(String chunkId,
                             int sequence,
                             String model,
                             String endpoint,
                             String role,
                             boolean success,
                             int attempts,
                             int retries,
                             long durationMs,
                             boolean timeout,
                             int statusCode,
                             boolean modelNotFound,
                             long requestBytes,
                             long responseBytes,
                             String lastError) {
            this.chunkId = chunkId;
            this.sequence = sequence;
            this.model = model;
            this.endpoint = endpoint;
            this.role = role;
            this.success = success;
            this.attempts = attempts;
            this.retries = retries;
            this.durationMs = durationMs;
            this.timeout = timeout;
            this.statusCode = statusCode;
            this.modelNotFound = modelNotFound;
            this.requestBytes = requestBytes;
            this.responseBytes = responseBytes;
            this.lastError = lastError;
        }

        Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("chunkId", chunkId);
            map.put("sequence", sequence);
            map.put("model", model);
            map.put("endpoint", endpoint);
            map.put("role", role);
            map.put("success", success);
            map.put("attempts", attempts);
            map.put("retries", retries);
            map.put("durationMs", durationMs);
            map.put("timeout", timeout);
            map.put("statusCode", statusCode);
            map.put("modelNotFound", modelNotFound);
            map.put("requestBytes", requestBytes);
            map.put("responseBytes", responseBytes);
            if (lastError != null && !lastError.isEmpty()) {
                map.put("lastError", lastError);
            }
            return map;
        }
    }

    public static final class RetentionIntegrityReport {
        private final int retentionDays;
        private final long cutoffEpochMs;
        private final long generatedAt;
        private final int totalCandidates;
        private final int sampledEntries;
        private final int chunkMismatches;
        private final int progressAnomalies;
        private final int metricAnomalies;
        private final int repairsApplied;
        private final List<Mismatch> mismatchSamples;
        private final List<JsonIssue> progressIssues;
        private final List<JsonIssue> metricIssues;
        private final List<RepairAction> repairActions;
        private final boolean repairAttempted;

        public RetentionIntegrityReport(int retentionDays,
                                 long cutoffEpochMs,
                                 long generatedAt,
                                 int totalCandidates,
                                 int sampledEntries,
                                 int chunkMismatches,
                                 int progressAnomalies,
                                 int metricAnomalies,
                                 int repairsApplied,
                                 List<Mismatch> mismatchSamples,
                                 List<JsonIssue> progressIssues,
                                 List<JsonIssue> metricIssues,
                                 List<RepairAction> repairActions,
                                 boolean repairAttempted) {
            this.retentionDays = retentionDays;
            this.cutoffEpochMs = cutoffEpochMs;
            this.generatedAt = generatedAt;
            this.totalCandidates = totalCandidates;
            this.sampledEntries = sampledEntries;
            this.chunkMismatches = chunkMismatches;
            this.progressAnomalies = progressAnomalies;
            this.metricAnomalies = metricAnomalies;
            this.repairsApplied = repairsApplied;
            this.mismatchSamples = mismatchSamples != null ? mismatchSamples : Collections.emptyList();
            this.progressIssues = progressIssues != null ? progressIssues : Collections.emptyList();
            this.metricIssues = metricIssues != null ? metricIssues : Collections.emptyList();
            this.repairActions = repairActions != null ? repairActions : Collections.emptyList();
            this.repairAttempted = repairAttempted;
        }

        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("retentionDays", retentionDays);
            map.put("cutoffEpochMs", cutoffEpochMs);
            map.put("generatedAt", generatedAt);
            map.put("totalCandidates", totalCandidates);
            map.put("sampledEntries", sampledEntries);
            map.put("chunkMismatches", chunkMismatches);
            map.put("progressAnomalies", progressAnomalies);
            map.put("metricsAnomalies", metricAnomalies);
            map.put("repairsApplied", repairsApplied);
            map.put("repairAttempted", repairAttempted);
            map.put("mismatchSamples", mismatchSamples.stream()
                    .map(Mismatch::toMap)
                    .collect(Collectors.toList()));
            map.put("progressIssues", progressIssues.stream()
                    .map(JsonIssue::toMap)
                    .collect(Collectors.toList()));
            map.put("metricIssues", metricIssues.stream()
                    .map(JsonIssue::toMap)
                    .collect(Collectors.toList()));
            map.put("repairActions", repairActions.stream()
                    .map(RepairAction::toMap)
                    .collect(Collectors.toList()));
            return map;
        }

        public static final class Mismatch {
            private final long historyId;
            private final String projectKey;
            private final String repositorySlug;
            private final long pullRequestId;
            private final int expectedChunks;
            private final int actualChunks;
            private final long reviewStartTime;

            public Mismatch(long historyId,
                     String projectKey,
                     String repositorySlug,
                     long pullRequestId,
                     int expectedChunks,
                     int actualChunks,
                     long reviewStartTime) {
                this.historyId = historyId;
                this.projectKey = projectKey;
                this.repositorySlug = repositorySlug;
                this.pullRequestId = pullRequestId;
                this.expectedChunks = expectedChunks;
                this.actualChunks = actualChunks;
                this.reviewStartTime = reviewStartTime;
            }

            Map<String, Object> toMap() {
                Map<String, Object> map = new LinkedHashMap<>();
                map.put("historyId", historyId);
                map.put("projectKey", projectKey);
                map.put("repositorySlug", repositorySlug);
                map.put("pullRequestId", pullRequestId);
                map.put("expectedChunks", expectedChunks);
                map.put("actualChunks", actualChunks);
                map.put("reviewStartTime", reviewStartTime);
                return map;
            }
        }

        public static final class JsonIssue {
            private final long historyId;
            private final String field;
            private final String message;

            public JsonIssue(long historyId, String field, String message) {
                this.historyId = historyId;
                this.field = field;
                this.message = message;
            }

            Map<String, Object> toMap() {
                Map<String, Object> map = new LinkedHashMap<>();
                map.put("historyId", historyId);
                map.put("field", field);
                map.put("message", message);
                return map;
            }
        }

        public static final class RepairAction {
            private final long historyId;
            private final String projectKey;
            private final String repositorySlug;
            private final long pullRequestId;
            private final List<String> actions;

            public RepairAction(long historyId,
                         String projectKey,
                         String repositorySlug,
                         long pullRequestId,
                         List<String> actions) {
                this.historyId = historyId;
                this.projectKey = projectKey;
                this.repositorySlug = repositorySlug;
                this.pullRequestId = pullRequestId;
                this.actions = actions != null ? new ArrayList<>(actions) : Collections.emptyList();
            }

            Map<String, Object> toMap() {
                Map<String, Object> map = new LinkedHashMap<>();
                map.put("historyId", historyId);
                map.put("projectKey", projectKey);
                map.put("repositorySlug", repositorySlug);
                map.put("pullRequestId", pullRequestId);
                map.put("actions", actions);
                return map;
            }
        }
    }

    private static final class JsonStatus {
        final boolean valid;
        final String message;

        private JsonStatus(boolean valid, String message) {
            this.valid = valid;
            this.message = message;
        }

        static JsonStatus valid() {
            return new JsonStatus(true, null);
        }

        static JsonStatus invalid(String message) {
            return new JsonStatus(false, message);
        }
    }

    private final class CircuitAggregation {
        long samples;
        long failureCount;
        long openEvents;
        long blockedCalls;
        long succeededCalls;
        long failedCalls;
        long clientBlockedCalls;
        long clientHardFailures;
        final Map<String, Long> stateCounts = new LinkedHashMap<>();

        void accept(@Nonnull Map<String, Object> metricsMap) {
            if (metricsMap.isEmpty()) {
                return;
            }
            Object raw = metricsMap.get("ai.model.circuit.snapshot");
            if (!(raw instanceof Map)) {
                return;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> snapshot = (Map<String, Object>) raw;
            samples++;
            String state = snapshot.get("state") != null
                    ? snapshot.get("state").toString()
                    : "UNKNOWN";
            stateCounts.merge(state.toUpperCase(Locale.ROOT), 1L, Long::sum);
            failureCount += asLong(snapshot.get("failureCount"), 0);
            openEvents += asLong(snapshot.get("openEvents"), 0);
            blockedCalls += asLong(snapshot.get("blockedCalls"), 0);
            succeededCalls += asLong(snapshot.get("succeededCalls"), 0);
            failedCalls += asLong(snapshot.get("failedCalls"), 0);
            clientBlockedCalls += asLong(snapshot.get("clientBlockedCalls"), 0);
            clientHardFailures += asLong(snapshot.get("clientHardFailures"), 0);
        }

        Map<String, Object> toMap() {
            if (samples == 0 && stateCounts.isEmpty()) {
                return Collections.emptyMap();
            }
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("samples", samples);
            map.put("stateCounts", stateCounts);
            map.put("failureCount", failureCount);
            map.put("openEvents", openEvents);
            map.put("blockedCalls", blockedCalls);
            map.put("succeededCalls", succeededCalls);
            map.put("failedCalls", failedCalls);
            map.put("clientBlockedCalls", clientBlockedCalls);
            map.put("clientHardFailures", clientHardFailures);
            map.put("avgFailuresPerSample", samples > 0 ? (double) failureCount / samples : 0d);
            map.put("avgBlockedCallsPerSample", samples > 0 ? (double) blockedCalls / samples : 0d);
            return map;
        }
    }

    private final class ChunkAggregation {
        long requestBytes;
        long responseBytes;
        long chunkCount;
        long timeoutCount;
        final Map<Integer, Long> statusCounts = new LinkedHashMap<>();

        void accept(AIReviewChunk chunk) {
            chunkCount++;
            requestBytes += Math.max(0, chunk.getRequestBytes());
            responseBytes += Math.max(0, chunk.getResponseBytes());
            if (chunk.isTimeout()) {
                timeoutCount++;
            }
            int statusCode = chunk.getStatusCode();
            if (statusCode > 0) {
                statusCounts.merge(statusCode, 1L, Long::sum);
            }
        }

        void accept(Map<String, Object> entry) {
            chunkCount++;
            requestBytes += Math.max(0, asLong(entry.get("requestBytes"), 0));
            responseBytes += Math.max(0, asLong(entry.get("responseBytes"), 0));
            if (asBoolean(entry.get("timeout"), false)) {
                timeoutCount++;
            }
            int statusCode = safeLongToInt(asLong(entry.get("statusCode"), 0));
            if (statusCode > 0) {
                statusCounts.merge(statusCode, 1L, Long::sum);
            }
        }
    }

    private static final class DailyStats {
        int reviews;
        double totalDurationSeconds;
        long totalIssues;
        long critical;
        long high;
        long medium;
        long low;
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

    static int safeLongToInt(long value) {
        if (value > Integer.MAX_VALUE) {
            return Integer.MAX_VALUE;
        }
        if (value < Integer.MIN_VALUE) {
            return Integer.MIN_VALUE;
        }
        return (int) value;
    }

    private long asLong(Object value, long defaultValue) {
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        if (value instanceof String) {
            try {
                return Long.parseLong(((String) value).trim());
            } catch (NumberFormatException ignored) {
            }
        }
        return defaultValue;
    }

    private boolean asBoolean(Object value, boolean defaultValue) {
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        if (value instanceof Number) {
            return ((Number) value).longValue() != 0;
        }
        if (value instanceof String) {
            String trimmed = ((String) value).trim();
            if ("true".equalsIgnoreCase(trimmed) || "false".equalsIgnoreCase(trimmed)) {
                return Boolean.parseBoolean(trimmed);
            }
            try {
                return Long.parseLong(trimmed) != 0;
            } catch (NumberFormatException ignored) {
            }
        }
        return defaultValue;
    }

    private String normalizeEndpoint(Object raw) {
        if (raw == null) {
            return "unknown";
        }
        String value = raw.toString().trim();
        if (value.isEmpty()) {
            return "unknown";
        }
        return value.replaceAll("/+$", "");
    }

    private String normalizeModel(Object raw) {
        return limitString(raw, 255);
    }

    private String limitString(Object value, int maxLength) {
        if (value == null) {
            return null;
        }
        String str = String.valueOf(value).trim();
        if (str.isEmpty()) {
            return null;
        }
        if (str.length() <= maxLength) {
            return str;
        }
        return str.substring(0, Math.max(0, maxLength));
    }
}
