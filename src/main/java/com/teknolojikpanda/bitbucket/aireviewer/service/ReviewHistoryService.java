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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

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
        map.put("metricsSnapshot", safeMetrics(history.getMetricsJson()));
        map.put("progress", safeProgress(history.getProgressJson()));

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
