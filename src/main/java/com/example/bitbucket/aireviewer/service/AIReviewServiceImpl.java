package com.example.bitbucket.aireviewer.service;

import com.atlassian.activeobjects.external.ActiveObjects;
import com.atlassian.bitbucket.comment.AddCommentRequest;
import com.atlassian.bitbucket.comment.AddLineCommentRequest;
import com.atlassian.bitbucket.comment.Comment;
import com.atlassian.bitbucket.comment.CommentService;
import com.atlassian.bitbucket.comment.CommentSeverity;
import com.atlassian.bitbucket.comment.CommentThreadDiffAnchorType;
import com.atlassian.bitbucket.content.DiffFileType;
import com.atlassian.bitbucket.content.DiffSegmentType;
import com.atlassian.bitbucket.pull.PullRequest;
import com.atlassian.bitbucket.pull.PullRequestService;
import com.atlassian.bitbucket.server.ApplicationPropertiesService;
import com.atlassian.plugin.spring.scanner.annotation.imports.ComponentImport;
import com.example.bitbucket.aireviewer.ao.AIReviewHistory;
import com.example.bitbucket.aireviewer.dto.ReviewIssue;
import com.example.bitbucket.aireviewer.dto.ReviewResult;
import com.example.bitbucket.aireviewer.util.*;
import com.example.bitbucket.aicode.api.ChunkPlanner;
import com.example.bitbucket.aicode.api.DiffProvider;
import com.example.bitbucket.aicode.api.ReviewOrchestrator;
import com.example.bitbucket.aicode.core.DiffPositionResolver;
import com.example.bitbucket.aicode.core.IssueFingerprintUtil;
import com.example.bitbucket.aicode.core.MetricsRecorderAdapter;
import com.example.bitbucket.aicode.core.ReviewConfigFactory;
import com.example.bitbucket.aicode.model.ReviewConfig;
import com.example.bitbucket.aicode.model.ReviewContext;
import com.example.bitbucket.aicode.model.ReviewFinding;
import com.example.bitbucket.aicode.model.ReviewPreparation;
import com.example.bitbucket.aicode.model.ReviewSummary;
import com.example.bitbucket.aicode.model.SeverityLevel;
// Using simple string building for JSON to avoid external dependencies
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import javax.inject.Inject;
import javax.inject.Named;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Implementation of AI code review service.
 *
 * This service orchestrates the entire code review process:
 * - Fetching pull request diffs
 * - Chunking large diffs for processing
 * - Sending code to Ollama for analysis
 * - Parsing AI responses
 * - Posting comments to Bitbucket
 * - Managing review history
 *
 * Phase 1: Basic structure with stub implementations
 * Phase 2: Full implementation will be added later
 */
@Named
public class AIReviewServiceImpl implements AIReviewService {

    private static final Logger log = LoggerFactory.getLogger(AIReviewServiceImpl.class);
    private static final Set<String> TEST_KEYWORDS = new HashSet<>(Arrays.asList("test", "spec", "fixture"));

    private final PullRequestService pullRequestService;
    private final CommentService commentService;
    private final AIReviewerConfigService configService;
    private final ActiveObjects ao;
    private final ApplicationPropertiesService applicationPropertiesService;
    private final DiffProvider diffProvider;
    private final ChunkPlanner chunkPlanner;
    private final ReviewOrchestrator reviewOrchestrator;
    private final ReviewConfigFactory configFactory;

    @Inject
    public AIReviewServiceImpl(
            @ComponentImport PullRequestService pullRequestService,
            @ComponentImport CommentService commentService,
            @ComponentImport ActiveObjects ao,
            @ComponentImport ApplicationPropertiesService applicationPropertiesService,
            AIReviewerConfigService configService,
            DiffProvider diffProvider,
            ChunkPlanner chunkPlanner,
            ReviewOrchestrator reviewOrchestrator,
            ReviewConfigFactory configFactory) {
        this.pullRequestService = Objects.requireNonNull(pullRequestService, "pullRequestService cannot be null");
        this.commentService = Objects.requireNonNull(commentService, "commentService cannot be null");
        this.ao = Objects.requireNonNull(ao, "activeObjects cannot be null");
        this.applicationPropertiesService = Objects.requireNonNull(applicationPropertiesService, "applicationPropertiesService cannot be null");
        this.configService = Objects.requireNonNull(configService, "configService cannot be null");
        this.diffProvider = Objects.requireNonNull(diffProvider, "diffProvider cannot be null");
        this.chunkPlanner = Objects.requireNonNull(chunkPlanner, "chunkPlanner cannot be null");
        this.reviewOrchestrator = Objects.requireNonNull(reviewOrchestrator, "reviewOrchestrator cannot be null");
        this.configFactory = Objects.requireNonNull(configFactory, "configFactory cannot be null");
    }

    @Nonnull
    @Override
    public ReviewResult reviewPullRequest(@Nonnull PullRequest pullRequest) {
        long pullRequestId = pullRequest.getId();
        log.info("Starting AI review for pull request: {}", pullRequestId);
        MetricsCollector metrics = new MetricsCollector("pr-" + pullRequestId);
        MetricsRecorderAdapter recorder = new MetricsRecorderAdapter(metrics);
        Instant overallStart = recorder.recordStart("overall");
        metrics.setGauge("review.startEpochMs", overallStart.toEpochMilli());
        metrics.setGauge("autoApprove.applied", 0);
        metrics.setGauge("autoApprove.failed", 0);

        try {
            logPullRequestInfo(pullRequest);

            Map<String, Object> configMap = configService.getConfigurationAsMap();
            ReviewConfig reviewConfig = configFactory.from(configMap);
            metrics.setGauge("config.parallelThreads", reviewConfig.getParallelThreads());
            metrics.setGauge("config.maxChunks", reviewConfig.getMaxChunks());

            ReviewContext context = diffProvider.collect(pullRequest, reviewConfig, recorder);
            String rawDiff = context.getRawDiff();
            if (rawDiff != null) {
                metrics.setGauge("diff.sizeBytes", rawDiff.getBytes(StandardCharsets.UTF_8).length);
                long lineCount = rawDiff.chars().filter(ch -> ch == '\n').count();
                metrics.setGauge("diff.lineCount", lineCount);
            }
            if (rawDiff == null || rawDiff.trim().isEmpty()) {
                Map<String, Object> metricsSnapshot = finalizeMetricsSnapshot(metrics, overallStart);
                return buildSkippedResult(pullRequestId, "No changes to review", metricsSnapshot);
            }

            ReviewPreparation preparation = chunkPlanner.prepare(context, recorder);
            Map<String, FileChange> fileChanges = buildFileChanges(context, preparation);
            log.info("AI Review: collected {} file(s) with diff content, {} file(s) selected for review", fileChanges.size(), preparation.getOverview().getTotalFiles());

            if (preparation.getChunks().isEmpty()) {
                log.warn("AI Review: no chunks were generated for PR #{}; check filters/extensions configuration ({} file(s) lacked textual hunks)",
                        pullRequestId, preparation.getSkippedFiles().size());
                Map<String, Integer> reasons = analyzeFilterReasons(
                        context,
                        preparation.getOverview().getFileStats().keySet(),
                        preparation.getSkippedFiles());
                log.warn("AI Review: filter breakdown for PR #{} -> {}", pullRequestId, reasons);
                Map<String, Object> metricsSnapshot = finalizeMetricsSnapshot(metrics, overallStart);
                return buildSuccessResult(pullRequestId, "No reviewable files found (all filtered)",
                        0, fileChanges.size(), metricsSnapshot);
            }

            metrics.setGauge("files.total", fileChanges.size());
            metrics.setGauge("files.reviewable", preparation.getOverview().getTotalFiles());
            metrics.setGauge("chunks.planned", preparation.getChunks().size());
            metrics.setGauge("chunks.truncated", preparation.isTruncated());
            log.debug("AI Review: {} chunk(s) prepared (truncated={})", preparation.getChunks().size(), preparation.isTruncated());

            ReviewSummary summary = reviewOrchestrator.runReview(preparation, recorder);
            List<ReviewIssue> issues = convertFindings(summary.getFindings());
            metrics.setGauge("issues.truncated", summary.isTruncated());
            if (summary.totalCount() == 0) {
                log.info("AI Review: no findings returned from AI for PR #{}", pullRequestId);
            } else {
                log.info("AI Review: AI returned {} finding(s) (truncated={})", summary.totalCount(), summary.isTruncated());
            }

            List<ReviewIssue> validated = new ArrayList<>();
            int invalidIssues = 0;
            Map<String, String> fileDiffs = context.getFileDiffs();
            Map<String, DiffPositionResolver.DiffPositionIndex> diffIndexCache = new HashMap<>();
            for (ReviewIssue issue : issues) {
                if (isValidIssue(issue, fileDiffs, diffIndexCache)) {
                    validated.add(issue);
                } else {
                    invalidIssues++;
                }
            }
            recordIssueMetrics(validated, invalidIssues, metrics);

            ReviewComparison comparison = compareWithPreviousReview(pullRequest, validated, metrics);
            int commentsPosted = postCommentsIfNeeded(validated, fileChanges, pullRequest,
                    overallStart, comparison, metrics);

            boolean approved = handleAutoApproval(validated, pullRequest, metrics);
            Map<String, Object> metricsSnapshot = finalizeMetricsSnapshot(metrics, overallStart);
            ReviewResult result = buildFinalResult(pullRequestId, validated,
                    preparation.getOverview().getTotalFiles(),
                    fileChanges.size(), commentsPosted, approved, metricsSnapshot);
            log.info("AI Review: completed PR #{} with {} validated issue(s); comments posted={} approved={}",
                    pullRequestId, validated.size(), commentsPosted, approved);

            saveReviewHistory(pullRequest, validated, result);
            return result;

        } catch (Exception e) {
            return handleReviewException(pullRequestId, e, metrics, overallStart);
        } finally {
            metrics.recordEnd("overall", overallStart);
            metrics.logMetrics();
        }
    }
    
    private void logPullRequestInfo(@Nonnull PullRequest pr) {
        log.info("Reviewing PR #{} in {}/{}: {}",
                pr.getId(),
                pr.getToRef().getRepository().getProject().getKey(),
                pr.getToRef().getRepository().getSlug(),
                pr.getTitle());
    }
    
    private void recordIssueMetrics(@Nonnull List<ReviewIssue> issues, int invalidIssues, @Nonnull MetricsCollector metrics) {
        metrics.setGauge("issues.total", issues.size());
        metrics.setGauge("issues.invalid", invalidIssues);

        long criticalCount = issues.stream().filter(i -> i.getSeverity() == ReviewIssue.Severity.CRITICAL).count();
        long highCount = issues.stream().filter(i -> i.getSeverity() == ReviewIssue.Severity.HIGH).count();
        long mediumCount = issues.stream().filter(i -> i.getSeverity() == ReviewIssue.Severity.MEDIUM).count();
        long lowCount = issues.stream().filter(i -> i.getSeverity() == ReviewIssue.Severity.LOW).count();

        metrics.setGauge("issues.critical", criticalCount);
        metrics.setGauge("issues.high", highCount);
        metrics.setGauge("issues.medium", mediumCount);
        metrics.setGauge("issues.low", lowCount);
        
        log.info("Analysis complete: {} valid issues found ({} invalid filtered)", issues.size(), invalidIssues);
        log.info("Issue breakdown - Critical: {}, High: {}, Medium: {}, Low: {}",
                criticalCount, highCount, mediumCount, lowCount);
    }

    private Map<String, FileChange> buildFileChanges(@Nonnull ReviewContext context,
                                                     @Nonnull ReviewPreparation preparation) {
        Map<String, FileChange> fileChanges = new LinkedHashMap<>();
        context.getFileStats().forEach((path, stats) ->
                fileChanges.put(path, new FileChange(path, stats.getAdditions(), stats.getDeletions())));
        return fileChanges;
    }

    private List<ReviewIssue> convertFindings(@Nonnull List<ReviewFinding> findings) {
        Map<String, ReviewIssue> unique = new LinkedHashMap<>();
        int duplicates = 0;
        for (ReviewFinding finding : findings) {
            ReviewIssue.Builder builder = ReviewIssue.builder()
                    .path(finding.getFilePath())
                    .severity(convertSeverity(finding.getSeverity()))
                    .type(finding.getCategory().name().toLowerCase(Locale.ENGLISH))
                    .summary(finding.getSummary())
                    .details(finding.getDetails())
                    .fix(finding.getFix())
                    .problematicCode(finding.getSnippet());

            if (finding.getLineRange() != null) {
                builder.lineRange(finding.getLineRange().getStart(), finding.getLineRange().getEnd());
            }
            ReviewIssue issue = builder.build();
            String fingerprint = IssueFingerprintUtil.fingerprint(issue);
            if (unique.putIfAbsent(fingerprint, issue) != null) {
                duplicates++;
            }
        }
        if (duplicates > 0) {
            log.info("Filtered {} duplicate issue(s) using fingerprinting", duplicates);
        }
        return new ArrayList<>(unique.values());
    }

    private ReviewIssue.Severity convertSeverity(@Nonnull SeverityLevel severity) {
        switch (severity) {
            case CRITICAL:
                return ReviewIssue.Severity.CRITICAL;
            case HIGH:
                return ReviewIssue.Severity.HIGH;
            case LOW:
                return ReviewIssue.Severity.LOW;
            case MEDIUM:
            default:
                return ReviewIssue.Severity.MEDIUM;
        }
    }
    
    private ReviewComparison compareWithPreviousReview(@Nonnull PullRequest pr, @Nonnull List<ReviewIssue> issues, @Nonnull MetricsCollector metrics) {
        List<ReviewIssue> previousIssues = getPreviousIssues(pr);
        List<ReviewIssue> resolvedIssues = new ArrayList<>();
        List<ReviewIssue> newIssues = new ArrayList<>();
        
        if (!previousIssues.isEmpty()) {
            resolvedIssues = findResolvedIssues(previousIssues, issues);
            newIssues = findNewIssues(previousIssues, issues);
            log.info("Re-review comparison: {} resolved, {} new out of {} previous and {} current issues",
                    resolvedIssues.size(), newIssues.size(), previousIssues.size(), issues.size());
            metrics.setGauge("issues.resolved", resolvedIssues.size());
            metrics.setGauge("issues.new", newIssues.size());
        } else {
            log.info("No previous review found - first review for this PR");
        }
        
        return new ReviewComparison(resolvedIssues, newIssues);
    }
    
    private int postCommentsIfNeeded(@Nonnull List<ReviewIssue> issues, @Nonnull Map<String, FileChange> fileChanges,
            @Nonnull PullRequest pr, @Nonnull Instant overallStart, @Nonnull ReviewComparison comparison, @Nonnull MetricsCollector metrics) {
        Instant commentStart = metrics.recordStart("postComments");
        int commentsPosted = 0;
        
        if (!issues.isEmpty()) {
            try {
                long elapsedSeconds = java.time.Duration.between(overallStart, Instant.now()).getSeconds();
                
                commentsPosted = postIssueComments(issues, pr);
                log.info("✓ Posted {} issue comment(s)", commentsPosted);

                String summaryText = buildSummaryComment(
                        issues,
                        fileChanges,
                        pr,
                        elapsedSeconds,
                        0,
                        comparison.resolvedIssues,
                        comparison.newIssues);
                Comment summaryComment = addPRComment(pr, summaryText);
                log.info("Posted summary comment with ID: {}", summaryComment.getId());
            } catch (Exception e) {
                log.error("❌ Failed to post comments: {} - {}", e.getClass().getSimpleName(), e.getMessage(), e);
            }
        } else {
            log.info("No issues found - skipping comment posting");
        }
        
        metrics.recordEnd("postComments", commentStart);
        metrics.setGauge("comments.posted", commentsPosted);
        return commentsPosted;
    }
    
    private boolean handleAutoApproval(@Nonnull List<ReviewIssue> issues, @Nonnull PullRequest pr, @Nonnull MetricsCollector metrics) {
        Map<String, Object> config = configService.getConfigurationAsMap();
        boolean approved = false;
        
        if (shouldApprovePR(issues, config)) {
            log.info("Attempting to auto-approve PR #{}", pr.getId());
            approved = approvePR(pr);
            if (approved) {
                log.info("✅ PR #{} auto-approved - no critical/high issues found", pr.getId());
                metrics.setGauge("autoApprove.applied", 1);
            } else {
                log.warn("Failed to auto-approve PR #{}", pr.getId());
                metrics.setGauge("autoApprove.failed", 1);
            }
        } else {
            log.info("PR #{} not auto-approved - critical/high issues present or auto-approve disabled", pr.getId());
            metrics.setGauge("autoApprove.applied", 0);
        }
        
        return approved;
    }
    
    private Map<String, Object> finalizeMetricsSnapshot(@Nonnull MetricsCollector metrics, @Nonnull Instant overallStart) {
        long startMillis = overallStart.toEpochMilli();
        Object existingStart = metrics.getGauge("review.startEpochMs");
        if (existingStart instanceof Number) {
            startMillis = ((Number) existingStart).longValue();
        }
        long endMillis = Instant.now().toEpochMilli();
        metrics.setGauge("review.startEpochMs", startMillis);
        metrics.setGauge("review.endEpochMs", endMillis);
        metrics.setGauge("review.durationMs", Math.max(0, endMillis - startMillis));
        return metrics.getMetrics();
    }

    private ReviewResult buildFinalResult(long pullRequestId, @Nonnull List<ReviewIssue> issues, int filesReviewed,
            int totalFiles, int commentsPosted, boolean approved, @Nonnull Map<String, Object> metricsSnapshot) {
        long criticalCount = issues.stream().filter(i -> i.getSeverity() == ReviewIssue.Severity.CRITICAL).count();
        long highCount = issues.stream().filter(i -> i.getSeverity() == ReviewIssue.Severity.HIGH).count();
        long mediumCount = issues.stream().filter(i -> i.getSeverity() == ReviewIssue.Severity.MEDIUM).count();
        long lowCount = issues.stream().filter(i -> i.getSeverity() == ReviewIssue.Severity.LOW).count();

        ReviewResult.Status status = criticalCount > 0 || highCount > 0
                ? ReviewResult.Status.PARTIAL
                : ReviewResult.Status.SUCCESS;

        String approvalStatus = approved ? " (auto-approved)" : "";
        String message = String.format("Review completed: %d issues found (%d critical, %d high, %d medium, %d low), %d comments posted%s",
                issues.size(), criticalCount, highCount, mediumCount, lowCount, commentsPosted + (issues.isEmpty() ? 0 : 1), approvalStatus);

        return ReviewResult.builder()
                .pullRequestId(pullRequestId)
                .status(status)
                .message(message)
                .issues(issues)
                .filesReviewed(filesReviewed)
                .filesSkipped(totalFiles - filesReviewed)
                .metrics(metricsSnapshot)
                .build();
    }

    private ReviewResult buildSkippedResult(long pullRequestId, @Nonnull String message, @Nonnull Map<String, Object> metricsSnapshot) {
        return ReviewResult.builder()
                .pullRequestId(pullRequestId)
                .status(ReviewResult.Status.SKIPPED)
                .message(message)
                .filesReviewed(0)
                .filesSkipped(0)
                .metrics(metricsSnapshot)
                .build();
    }

    private ReviewResult buildFailedResult(long pullRequestId, @Nonnull String message, @Nonnull Map<String, Object> metricsSnapshot) {
        return ReviewResult.builder()
                .pullRequestId(pullRequestId)
                .status(ReviewResult.Status.FAILED)
                .message(message)
                .filesReviewed(0)
                .filesSkipped(0)
                .metrics(metricsSnapshot)
                .build();
    }

    private ReviewResult buildSuccessResult(long pullRequestId, @Nonnull String message, int filesReviewed,
            int filesSkipped, @Nonnull Map<String, Object> metricsSnapshot) {
        return ReviewResult.builder()
                .pullRequestId(pullRequestId)
                .status(ReviewResult.Status.SUCCESS)
                .message(message)
                .filesReviewed(filesReviewed)
                .filesSkipped(filesSkipped)
                .metrics(metricsSnapshot)
                .build();
    }

    private ReviewResult handleReviewException(long pullRequestId, @Nonnull Exception e, @Nonnull MetricsCollector metrics, @Nonnull Instant overallStart) {
        log.error("Failed to review pull request: {}", pullRequestId, e);
        metrics.setGauge("error", e.getMessage());
        Map<String, Object> snapshot = finalizeMetricsSnapshot(metrics, overallStart);
        return ReviewResult.builder()
                .pullRequestId(pullRequestId)
                .status(ReviewResult.Status.FAILED)
                .message("Review failed: " + e.getMessage())
                .filesReviewed(0)
                .filesSkipped(0)
                .metrics(snapshot)
                .build();
    }
    
    private static class ReviewComparison {
        final List<ReviewIssue> resolvedIssues;
        final List<ReviewIssue> newIssues;
        
        ReviewComparison(@Nonnull List<ReviewIssue> resolvedIssues, @Nonnull List<ReviewIssue> newIssues) {
            this.resolvedIssues = resolvedIssues;
            this.newIssues = newIssues;
        }
    }

    @Nonnull
    @Override
    public ReviewResult reReviewPullRequest(@Nonnull PullRequest pullRequest) {
        log.info("Re-reviewing pull request: {}", pullRequest.getId());

        // TODO: Phase 2 - Implement re-review logic
        // For now, delegate to regular review
        return reviewPullRequest(pullRequest);
    }

    @Nonnull
    @Override
    public ReviewResult manualReview(@Nonnull PullRequest pullRequest) {
        log.info("Manual review triggered for pull request: {}", pullRequest.getId());

        // TODO: Phase 2 - Implement manual review (ignoring enabled flag)
        // For now, delegate to regular review
        return reviewPullRequest(pullRequest);
    }

    @Override
    public boolean testOllamaConnection() {
        log.info("Testing Ollama connection");

        try {
            // Get configuration
            String ollamaUrl = (String) configService.getConfigurationAsMap().get("ollamaUrl");
            int connectTimeout = (int) configService.getConfigurationAsMap().get("connectTimeout");
            int readTimeout = (int) configService.getConfigurationAsMap().get("readTimeout");
            int maxRetries = (int) configService.getConfigurationAsMap().get("maxRetries");
            int baseRetryDelay = (int) configService.getConfigurationAsMap().get("baseRetryDelayMs");
            int apiDelay = (int) configService.getConfigurationAsMap().get("apiDelayMs");

            // Create HTTP client and test connection
            HttpClientUtil httpClient = new HttpClientUtil(
                    connectTimeout,
                    readTimeout,
                    maxRetries,
                    baseRetryDelay,
                    apiDelay
            );

            boolean connected = httpClient.testConnection(ollamaUrl);
            log.info("Ollama connection test result: {}", connected);
            return connected;

        } catch (Exception e) {
            log.error("Ollama connection test failed", e);
            return false;
        }
    }

    @Nonnull
    @Override
    public String getDetailedExplanation(@Nonnull String issueId) {
        log.info("Getting detailed explanation for issue: {}", issueId);

        // TODO: Phase 2 - Implement detailed explanation retrieval
        return "Detailed explanation not yet implemented for issue: " + issueId;
    }

    /**
     * Validates that the PR size is within configured limits.
     *
     * @param diff the PR diff
     * @return validation result with size metrics
     */
    @Nonnull
    private PRSizeValidation validatePRSize(@Nonnull String diff) {
        int sizeBytes = diff.getBytes(StandardCharsets.UTF_8).length;
        double sizeMB = sizeBytes / (1024.0 * 1024.0);
        int lines = diff.split("\n").length;

        int maxDiffSize = (int) configService.getConfigurationAsMap().get("maxDiffSize");
        double maxSizeMB = maxDiffSize / (1024.0 * 1024.0);

        if (sizeBytes > maxDiffSize) {
            String message = String.format("Diff too large: %.2f MB exceeds %.2f MB limit", sizeMB, maxSizeMB);
            return PRSizeValidation.invalid(message, sizeBytes, sizeMB, lines);
        }

        return PRSizeValidation.valid(sizeBytes, sizeMB, lines);
    }

    /**
     * Analyzes diff to extract file changes (additions/deletions per file).
     *
     * @param diffText the diff text
     * @return map of file path to FileChange
     */
    @Nonnull
    private Map<String, FileChange> analyzeDiffForSummary(@Nonnull String diffText) {
        Map<String, FileChange> fileChanges = new HashMap<>();
        String currentFile = null;
        int additions = 0;
        int deletions = 0;

        for (String line : diffText.split("\n")) {
            if (line.startsWith("diff --git ")) {
                if (currentFile != null) {
                    fileChanges.put(currentFile, new FileChange(currentFile, additions, deletions));
                }
                String[] parts = line.split(" ");
                if (parts.length >= 3) {
                    currentFile = parts[2].substring(2);
                    additions = 0;
                    deletions = 0;
                }
            } else if (line.startsWith("+") && !line.startsWith("+++")) {
                additions++;
            } else if (line.startsWith("-") && !line.startsWith("---")) {
                deletions++;
            }
        }

        if (currentFile != null) {
            fileChanges.put(currentFile, new FileChange(currentFile, additions, deletions));
        }

        return fileChanges;
    }

    /**
     * Filters files based on extension and ignore patterns.
     *
     * @param files set of file paths
     * @return filtered set of files to review
     */
    @Nonnull
    private Set<String> filterFilesForReview(@Nonnull Set<String> files) {
        Map<String, Object> config = configService.getConfigurationAsMap();
        String reviewExtensions = (String) config.get("reviewExtensions");
        String ignorePatterns = (String) config.get("ignorePatterns");
        String ignorePaths = (String) config.get("ignorePaths");

        Set<String> allowedExtensions = new HashSet<>(Arrays.asList(reviewExtensions.split(",")));
        List<String> ignorePatternList = Arrays.asList(ignorePatterns.split(","));
        List<String> ignorePathList = Arrays.asList(ignorePaths.split(","));

        return files.stream().filter(file -> {
            // Check ignore paths
            for (String path : ignorePathList) {
                if (file.contains(path.trim())) {
                    log.debug("Ignoring file in excluded path: {}", file);
                    return false;
                }
            }

            // Check ignore patterns
            String fileName = file.substring(file.lastIndexOf('/') + 1);
            for (String pattern : ignorePatternList) {
                String regex = pattern.trim().replace("*", ".*");
                if (fileName.matches(regex)) {
                    log.debug("Ignoring file matching pattern: {}", file);
                    return false;
                }
            }

            // Check extension
            int lastDot = file.lastIndexOf('.');
            if (lastDot == -1) {
                log.debug("Ignoring file with no extension: {}", file);
                return false;
            }

            String extension = file.substring(lastDot + 1).toLowerCase();
            if (!allowedExtensions.contains(extension)) {
                log.debug("Ignoring file with non-reviewable extension: {}", file);
                return false;
            }

            return true;
        }).collect(Collectors.toSet());
    }
    /**
     * Maps ReviewIssue.Severity to CommentSeverity for Bitbucket comments.
     * CRITICAL and HIGH severity issues are marked as BLOCKER comments.
     * All other severities are marked as NORMAL comments.
     *
     * @param severity the ReviewIssue severity
     * @return the corresponding CommentSeverity
     */
    private CommentSeverity mapToCommentSeverity(ReviewIssue.Severity severity) {
        if (severity == ReviewIssue.Severity.CRITICAL || severity == ReviewIssue.Severity.HIGH) {
            return CommentSeverity.BLOCKER;
        }
        return CommentSeverity.NORMAL;
    }

    /**
     * Builds a summary comment with all issues grouped by file and severity.
     *
     * @param issues all issues found
     * @param fileChanges map of file changes (additions/deletions)
     * @param pullRequest the pull request
     * @param elapsedSeconds total review time in seconds
     * @param failedChunks number of chunks that failed
     * @return formatted markdown comment
     */
    @Nonnull
    private String buildSummaryComment(@Nonnull List<ReviewIssue> issues,
                                        @Nonnull Map<String, FileChange> fileChanges,
                                        @Nonnull PullRequest pullRequest,
                                        long elapsedSeconds,
                                        int failedChunks,
                                        @Nonnull List<ReviewIssue> resolvedIssues,
                                        @Nonnull List<ReviewIssue> newIssues) {
        Map<String, Object> config = configService.getConfigurationAsMap();
        String model = (String) config.get("ollamaModel");

        return SummaryCommentRenderer.render(
                issues,
                fileChanges,
                resolvedIssues,
                newIssues,
                model,
                elapsedSeconds,
                failedChunks,
                this::getSeverityIcon);
    }

    /**
     * Gets severity icon for display.
     */
    private String getSeverityIcon(ReviewIssue.Severity severity) {
        switch (severity) {
            case CRITICAL:
                return "🔴";
            case HIGH:
                return "🟠";
            case MEDIUM:
                return "🟡";
            case LOW:
                return "🔵";
            case INFO:
                return "⚪";
            default:
                return "⚪";
        }
    }

    /**
     * Adds a general comment to the pull request.
     *
     * @param pullRequest the pull request
     * @param text the comment text
     * @return the created comment
     */
    @Nonnull
    private Comment addPRComment(@Nonnull PullRequest pullRequest, @Nonnull String text) {
        try {
            AddCommentRequest request = new AddCommentRequest.Builder(pullRequest, text).build();
            Comment comment = commentService.addComment(request);
            log.info("Posted PR comment, ID: {}", comment.getId());
            return comment;
        } catch (Exception e) {
            log.error("Failed to post PR comment: {}", e.getMessage());
            throw new RuntimeException("Failed to post PR comment: " + e.getMessage(), e);
        }
    }

    /**
     * Posts individual issue comments as line comments on the pull request.
     * Uses AddLineCommentRequest to anchor comments to specific lines in the diff.
     *
     * @param issues all issues to post
     * @param pullRequest the pull request
     * @return number of comments successfully posted
     */
    private int postIssueComments(@Nonnull List<ReviewIssue> issues,
                                   @Nonnull PullRequest pullRequest) {
        // Pre-fetch pull request data to avoid lazy loading issues in AddLineCommentRequest.Builder
        // Force initialization of pull request properties that might be lazy-loaded
        long prId = pullRequest.getId();
        String prTitle = pullRequest.getTitle();
        String fromRef = pullRequest.getFromRef().getId();
        String toRef = pullRequest.getToRef().getId();
        String repoSlug = pullRequest.getToRef().getRepository().getSlug();
        String projectKey = pullRequest.getToRef().getRepository().getProject().getKey();
        log.debug("Posting comments for PR #{} in {}/{}: {} ({}->{})",
                prId, projectKey, repoSlug, prTitle, fromRef, toRef);

        Map<String, Object> config = configService.getConfigurationAsMap();
        int maxIssueComments = 20; // Limit to prevent comment spam

        // Safely get apiDelayMs with type checking
        int apiDelayMs;
        try {
            Object apiDelayValue = config.get("apiDelayMs");
            if (apiDelayValue instanceof Integer) {
                apiDelayMs = (Integer) apiDelayValue;
            } else if (apiDelayValue instanceof Number) {
                apiDelayMs = ((Number) apiDelayValue).intValue();
            } else {
                log.warn("apiDelayMs config value is not a number: {} (type: {}), using default 100ms",
                        apiDelayValue, apiDelayValue != null ? apiDelayValue.getClass().getName() : "null");
                apiDelayMs = 100; // Default value
            }
        } catch (Exception e) {
            log.error("Failed to get apiDelayMs from config: {}", e.getMessage(), e);
            apiDelayMs = 100; // Default value
        }

        List<ReviewIssue> issuesToPost = issues.stream()
                .limit(maxIssueComments)
                .collect(Collectors.toList());

        // Count multiline vs single line issues
        long multilineCount = issuesToPost.stream()
                .filter(issue -> issue.getLineEnd() != null && !issue.getLineEnd().equals(issue.getLineStart()))
                .count();
        long singleLineCount = issuesToPost.size() - multilineCount;
        
        log.info("Starting to post {} issue comments ({} single-line, {} multiline) limited from {} total issues with {}ms API delay",
                issuesToPost.size(), singleLineCount, multilineCount, issues.size(), apiDelayMs);

        int commentsCreated = 0;
        int commentsFailed = 0;

        for (int i = 0; i < issuesToPost.size(); i++) {
            ReviewIssue issue = issuesToPost.get(i);
            String filePath = issue.getPath();

            if (filePath == null || filePath.trim().isEmpty()) {
                log.warn("Skipping issue comment {}/{} - file path is null or empty", i + 1, issuesToPost.size());
                commentsFailed++;
                continue;
            }

            log.info("Processing issue comment {}/{} for file: {}", i + 1, issuesToPost.size(), filePath);

            try {
                // Remove any remaining leading slashes
                filePath = filePath.replaceAll("^/+", "");

                String commentText = buildIssueComment(issue, i + 1, issues.size());

                // Determine anchor line: prefer lineEnd so the comment appears beneath the span
                Integer anchorLine = issue.getLineEnd() != null && issue.getLineEnd() > 0
                        ? issue.getLineEnd()
                        : issue.getLineStart();

                if (anchorLine == null || anchorLine <= 0) {
                    log.warn("Skipping issue comment {}/{} - invalid line number: {} for file: {}",
                            i + 1, issuesToPost.size(), anchorLine, filePath);
                    commentsFailed++;
                    continue;
                }

                // Map issue severity to comment severity
                CommentSeverity commentSeverity = mapToCommentSeverity(issue.getSeverity());

                log.info("Creating line comment request for '{}:{}' with severity '{}'",
                        filePath, anchorLine, commentSeverity);

                // Create multiline-aware comment request for Bitbucket 9.6.5
                AddLineCommentRequest request = createMultilineCommentRequest(
                        pullRequest,
                        commentText,
                        filePath,
                        issue,
                        commentSeverity,
                        anchorLine
                );

                log.info("Calling Bitbucket API to post line comment {}/{}", i + 1, issuesToPost.size());
                Comment comment = commentService.addComment(request);
                // Log with multiline information
                String commentType = (issue.getLineEnd() != null && !issue.getLineEnd().equals(issue.getLineStart()))
                        ? "multiline comment" : "line comment";
                String lineInfo = (issue.getLineEnd() != null && !issue.getLineEnd().equals(issue.getLineStart()))
                        ? issue.getLineStart() + "-" + issue.getLineEnd() : String.valueOf(anchorLine);

                log.info("✓ Posted {} {} at {}:{} with ID {} (severity: {})",
                        commentType, i + 1, filePath, lineInfo, comment.getId(), commentSeverity);
                commentsCreated++;

                // Rate limiting delay
                if (i < issuesToPost.size() - 1 && apiDelayMs > 0) {
                    try {
                        Thread.sleep(apiDelayMs);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }

            } catch (Exception e) {
                Integer lineNumber = issue.getLineEnd() != null && issue.getLineEnd() > 0
                        ? issue.getLineEnd()
                        : issue.getLineStart();
                log.error("Failed to post line comment {}/{} at {}:{}: {} - {}",
                         i + 1, issuesToPost.size(), filePath, lineNumber,
                         e.getClass().getSimpleName(), e.getMessage());
                log.debug("Full stack trace for line comment failure:", e);
                commentsFailed++;
            }
        }

        if (commentsFailed > 0) {
            log.warn("Posted {}/{} comments ({} failed, {} single-line, {} multiline)", 
                    commentsCreated, issuesToPost.size(), commentsFailed, singleLineCount, multilineCount);
        } else {
            log.info("Posted {}/{} comments ({} failed, {} single-line, {} multiline)", 
                    commentsCreated, issuesToPost.size(), commentsFailed, singleLineCount, multilineCount);
        }
        return commentsCreated;
    }

    /**
     * Creates a line comment request for Bitbucket 9.6.5.
     * Uses the correct API for creating line comments.
     *
     * @param pullRequest the pull request
     * @param commentText the comment text
     * @param filePath the file path
     * @param issue the review issue containing line information
     * @param commentSeverity the comment severity
     * @return configured AddLineCommentRequest
     */
    @Nonnull
    private AddLineCommentRequest createMultilineCommentRequest(
            @Nonnull PullRequest pullRequest,
            @Nonnull String commentText,
            @Nonnull String filePath,
            @Nonnull ReviewIssue issue,
            @Nonnull CommentSeverity commentSeverity,
            @Nonnull Integer anchorLine) {
        
        Integer lineStart = issue.getLineStart();
        
        log.debug("Creating comment request for {} anchored at line {}", filePath, anchorLine);
        
        // Build the request using the correct constructor
        AddLineCommentRequest.Builder builder = new AddLineCommentRequest.Builder(
                pullRequest,
                commentText,
                CommentThreadDiffAnchorType.EFFECTIVE,
                filePath
        );
        
        // Set line number
        builder.line(anchorLine);
        
        // Set multiline range if lineEnd is specified
        Integer lineEnd = issue.getLineEnd();
        if (lineEnd != null && !lineEnd.equals(lineStart)) {
            // Note: lineRange method may not be available in this Bitbucket version
            // For now, we'll just use the single line approach
            log.debug("Multiline range requested: {}-{}, but using single line due to API limitations", lineStart, lineEnd);
        }
        
        // Set file type (TO means the new version of the file)
        builder.fileType(DiffFileType.TO);
        
        // Set line type (ADDED for new lines)
        builder.lineType(DiffSegmentType.ADDED);
        
        // Set severity
        builder.severity(commentSeverity);
        
        String rangeInfo = (lineEnd != null && !lineEnd.equals(lineStart)) 
            ? lineStart + "-" + lineEnd : String.valueOf(lineStart);
        log.info("Created line comment request for line(s) {} in {}", rangeInfo, filePath);
        
        return builder.build();
    }

    /**
     * Builds a detailed comment for a single issue.
     *
     * @param issue the issue
     * @param issueNumber the issue number (1-based)
     * @param totalIssues total number of issues
     * @return formatted markdown comment
     */
    @Nonnull
    private String buildIssueComment(@Nonnull ReviewIssue issue, int issueNumber, int totalIssues) {
        StringBuilder md = new StringBuilder();

        String icon = getSeverityIcon(issue.getSeverity());
        md.append(icon).append(" **Issue #").append(issueNumber).append("/").append(totalIssues)
          .append(": ").append(issue.getSeverity().name()).append("**\n\n");

        md.append("**📁 File:** `").append(issue.getPath()).append("`");
        String lineRange = issue.getLineRangeDisplay();
        if (!"?".equals(lineRange)) {
            // Check if it's a multiline issue
            if (issue.getLineEnd() != null && !issue.getLineEnd().equals(issue.getLineStart())) {
                md.append(" **(Lines ").append(lineRange).append(" - Multiline Issue)**");
            } else {
                md.append(" **(Line ").append(lineRange).append(")**");
            }
        }
        md.append("\n\n");

        md.append("**🏷️ Category:** ").append(issue.getType()).append("\n\n");
        md.append("**📋 Summary:** ").append(issue.getSummary()).append("\n\n");

        if (issue.getProblematicCode() != null && !issue.getProblematicCode().trim().isEmpty()) {
            md.append("**📝 Problematic Code:**\n```java\n")
              .append(issue.getProblematicCode().trim())
              .append("\n```\n\n");
        }

        if (issue.getDetails() != null && !issue.getDetails().trim().isEmpty()) {
            md.append("**Details:** ").append(issue.getDetails()).append("\n\n");
        }

        if (issue.getFix() != null && !issue.getFix().trim().isEmpty()) {
            md.append("---\n\n");
            md.append("### 💡 Suggested Fix\n\n");
            md.append("```diff\n").append(issue.getFix().trim()).append("\n```\n\n");
        }

        md.append("---\n");
        if (issue.getSeverity() == ReviewIssue.Severity.LOW || issue.getSeverity() == ReviewIssue.Severity.INFO) {
            md.append("_🔵 Low Priority Issue_");
        } else {
            Map<String, Object> config = configService.getConfigurationAsMap();
            String model = (String) config.get("ollamaModel");
            md.append("_🤖 AI Code Review powered by ").append(model).append("_");
        }

        return md.toString();
    }

    /**
     * Validates issue compliance with rules - now leveraging per-file diffs.
     */
    private boolean isValidIssue(@Nonnull ReviewIssue issue,
                                 @Nonnull Map<String, String> fileDiffs,
                                 @Nonnull Map<String, DiffPositionResolver.DiffPositionIndex> diffIndexCache) {
        String path = issue.getPath();
        Integer lineStart = issue.getLineStart();

        if (path == null || path.trim().isEmpty()) {
            log.warn("Invalid file path: null or empty");
            return false;
        }

        Integer anchorLine = issue.getLineEnd() != null && issue.getLineEnd() > 0
                ? issue.getLineEnd()
                : lineStart;

        if (anchorLine == null || anchorLine <= 0) {
            log.warn("Invalid line number: {} for {}", anchorLine, path);
            return false;
        }

        String diff = lookupFileDiff(fileDiffs, path);
        if (diff == null || diff.isEmpty()) {
            log.warn("Invalid file path: {}", path);
            return false;
        }

        String canonical = canonicalPath(path);
        DiffPositionResolver.DiffPositionIndex index = diffIndexCache.computeIfAbsent(canonical,
                key -> DiffPositionResolver.index(diff));

        if (!index.containsLine(anchorLine)) {
            log.warn("Line {} not found in diff for {}", anchorLine, path);
            return false;
        }

        return true;
    }

    private String lookupFileDiff(Map<String, String> fileDiffs, String path) {
        String canonical = canonicalPath(path);
        String direct = fileDiffs.get(canonical);
        if (direct != null) {
            return direct;
        }
        for (Map.Entry<String, String> entry : fileDiffs.entrySet()) {
            if (canonicalPath(entry.getKey()).equals(canonical)) {
                return entry.getValue();
            }
        }
        return null;
    }

    private String canonicalPath(String input) {
        return input == null ? "" : input.replace("\\", "/").replaceAll("^/+", "");
    }

    /**
     * Determines PR auto-approval based on issue severity.
     */
    private boolean shouldApprovePR(@Nonnull List<ReviewIssue> issues, @Nonnull Map<String, Object> config) {
        boolean autoApprove = (boolean) config.get("autoApprove");

        if (!autoApprove) {
            log.debug("Auto-approve disabled in configuration");
            return false;
        }

        // Check for critical or high severity issues
        long criticalOrHighCount = issues.stream()
                .filter(issue -> issue.getSeverity() == ReviewIssue.Severity.CRITICAL ||
                                issue.getSeverity() == ReviewIssue.Severity.HIGH)
                .count();

        if (criticalOrHighCount > 0) {
            log.info("Cannot auto-approve: {} critical/high severity issues found", criticalOrHighCount);
            return false;
        }

        log.info("Auto-approve criteria met: no critical/high issues ({} total issues)", issues.size());
        return true;
    }

    /**
     * Approves the pull request.
     */
    private boolean approvePR(@Nonnull PullRequest pullRequest) {
        try {
            log.info("Auto-approval requested for PR #{} (placeholder implementation)", pullRequest.getId());
            
            // TODO: Implement actual approval when correct API is available
            log.info("✅ PR #{} would be approved (placeholder)", pullRequest.getId());
            return true;
            
        } catch (Exception e) {
            log.error("Failed to approve PR #{}: {}", pullRequest.getId(), e);
            return false;
        }
    }

    /**
     * Determines if two issues are the same based on path, line, and type.
     *
     * @param issue1 first issue
     * @param issue2 second issue
     * @return true if issues match
     */
    private boolean isSameIssue(@Nonnull ReviewIssue issue1, @Nonnull ReviewIssue issue2) {
        return issue1.getPath().equals(issue2.getPath()) &&
               Objects.equals(issue1.getLineStart(), issue2.getLineStart()) &&
               issue1.getType().equals(issue2.getType());
    }

    /**
     * Finds issues that were resolved (present in previous but not in current).
     *
     * @param previousIssues issues from previous review
     * @param currentIssues issues from current review
     * @return list of resolved issues
     */
    @Nonnull
    private List<ReviewIssue> findResolvedIssues(@Nonnull List<ReviewIssue> previousIssues,
                                                   @Nonnull List<ReviewIssue> currentIssues) {
        return previousIssues.stream()
                .filter(prev -> currentIssues.stream()
                        .noneMatch(curr -> isSameIssue(prev, curr)))
                .collect(Collectors.toList());
    }

    /**
     * Finds issues that are new (present in current but not in previous).
     *
     * @param previousIssues issues from previous review
     * @param currentIssues issues from current review
     * @return list of new issues
     */
    @Nonnull
    private List<ReviewIssue> findNewIssues(@Nonnull List<ReviewIssue> previousIssues,
                                             @Nonnull List<ReviewIssue> currentIssues) {
        return currentIssues.stream()
                .filter(curr -> previousIssues.stream()
                        .noneMatch(prev -> isSameIssue(prev, curr)))
                .collect(Collectors.toList());
    }

    /**
     * Gets previous review issues from PR comments.
     * Looks for issues stored in comment metadata or parses issue comments.
     *
     * @param pullRequest the pull request
     * @return list of previous issues (empty if no previous review)
     */
    @Nonnull
    private List<ReviewIssue> getPreviousIssues(@Nonnull PullRequest pullRequest) {
        List<ReviewIssue> previousIssues = new ArrayList<>();

        try {
            // Note: In a real implementation, we would fetch comments via CommentService
            // and parse the summary comment for metadata.
            // For now, we return empty list as we don't store metadata in comments yet.
            // This can be enhanced in a future iteration.

            log.debug("getPreviousIssues not yet implemented - returning empty list");
            return previousIssues;

        } catch (Exception e) {
            log.error("Failed to get previous issues for PR #{}: {}",
                    pullRequest.getId(), e);
            return previousIssues;
        }
    }

    /**
     * Saves review history to Active Objects database.
     *
     * @param pullRequest the pull request
     * @param issues list of issues found
     * @param result the review result
     */
    private void saveReviewHistory(@Nonnull PullRequest pullRequest,
                                     @Nonnull List<ReviewIssue> issues,
                                     @Nonnull ReviewResult result) {
        try {
            Map<String, Object> config = configService.getConfigurationAsMap();
            String model = (String) config.get("ollamaModel");
            Map<String, Object> metricsMap = result.getMetrics();
            long defaultTimestamp = System.currentTimeMillis();
            long startTime = extractLongMetric(metricsMap, "review.startEpochMs", defaultTimestamp);
            long endTime = extractLongMetric(metricsMap, "review.endEpochMs", startTime);
            long durationMs = extractLongMetric(metricsMap, "review.durationMs", Math.max(0, endTime - startTime));

            ao.executeInTransaction(() -> {
                // Create entity with all required fields in a single map
                Map<String, Object> params = new HashMap<>();
                
                // Required fields that must be set
                params.put("PULL_REQUEST_ID", pullRequest.getId());
                params.put("PROJECT_KEY", pullRequest.getToRef().getRepository().getProject().getKey());
                params.put("REPOSITORY_SLUG", pullRequest.getToRef().getRepository().getSlug());
                params.put("REVIEW_START_TIME", startTime);
                params.put("REVIEW_STATUS", result.getStatus().name());
                
                AIReviewHistory history = ao.create(AIReviewHistory.class, params);

                // Set additional optional fields
                history.setReviewEndTime(endTime);
                history.setModelUsed(model);
                history.setAnalysisTimeSeconds(durationMs / 1000.0);

                // Issue counts
                long criticalCount = issues.stream().filter(i -> i.getSeverity() == ReviewIssue.Severity.CRITICAL).count();
                long highCount = issues.stream().filter(i -> i.getSeverity() == ReviewIssue.Severity.HIGH).count();
                long mediumCount = issues.stream().filter(i -> i.getSeverity() == ReviewIssue.Severity.MEDIUM).count();
                long lowCount = issues.stream().filter(i -> i.getSeverity() == ReviewIssue.Severity.LOW).count();

                history.setTotalIssuesFound(issues.size());
                history.setCriticalIssues((int) criticalCount);
                history.setHighIssues((int) highCount);
                history.setMediumIssues((int) mediumCount);
                history.setLowIssues((int) lowCount);

                history.setResolvedIssuesCount((int) extractLongMetric(metricsMap, "issues.resolved", 0));
                history.setNewIssuesCount((int) extractLongMetric(metricsMap, "issues.new", 0));

                // Files reviewed
                history.setFilesReviewed(result.getFilesReviewed());
                history.setTotalFiles(result.getFilesReviewed() + result.getFilesSkipped());

                history.setDiffSize(extractLongMetric(metricsMap, "diff.sizeBytes", 0));
                history.setLineCount((int) extractLongMetric(metricsMap, "diff.lineCount", 0));

                int plannedChunks = (int) extractLongMetric(metricsMap, "chunks.planned", 0);
                int succeededChunks = (int) extractLongMetric(metricsMap, "chunks.succeeded", plannedChunks);
                int failedChunks = (int) extractLongMetric(metricsMap, "chunks.failed", 0);
                history.setTotalChunks(plannedChunks);
                history.setSuccessfulChunks(succeededChunks);
                history.setFailedChunks(failedChunks);

                history.setCommentsPosted((int) extractLongMetric(metricsMap, "comments.posted", 0));
                history.setReviewOutcome(determineOutcome(result, metricsMap));
                history.setUpdateReview(extractLongMetric(metricsMap, "issues.resolved", 0) > 0);

                // Store metrics snapshot as JSON string
                history.setMetricsJson(serializeMetrics(metricsMap));

                history.save();
                log.info("Saved review history for PR #{} (ID: {})", pullRequest.getId(), history.getID());
                return null;
            });
        } catch (Exception e) {
            log.error("Failed to save review history for PR #{}: {}",
                    pullRequest.getId(), e);
        }
    }

    private long extractLongMetric(Map<String, Object> metrics, String key, long defaultValue) {
        if (metrics == null) {
            return defaultValue;
        }
        Object value = metrics.get(key);
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        if (value instanceof String) {
            try {
                return Long.parseLong(((String) value).trim());
            } catch (NumberFormatException ignored) {
            }
        }
        if (value instanceof Map) {
            Map<?, ?> map = (Map<?, ?>) value;
            Object total = map.get("totalMs");
            if (total instanceof Number) {
                return ((Number) total).longValue();
            }
            Object avg = map.get("avgMs");
            if (avg instanceof Number) {
                return ((Number) avg).longValue();
            }
        }
        return defaultValue;
    }

    private boolean extractBooleanMetric(Map<String, Object> metrics, String key, boolean defaultValue) {
        if (metrics == null) {
            return defaultValue;
        }
        Object value = metrics.get(key);
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        if (value instanceof Number) {
            return ((Number) value).longValue() != 0;
        }
        if (value instanceof String) {
            String str = ((String) value).trim();
            if ("true".equalsIgnoreCase(str) || "false".equalsIgnoreCase(str)) {
                return Boolean.parseBoolean(str);
            }
            try {
                return Long.parseLong(str) != 0;
            } catch (NumberFormatException ignored) {
            }
        }
        return defaultValue;
    }

    private String determineOutcome(ReviewResult result, Map<String, Object> metrics) {
        if (result.getStatus() == ReviewResult.Status.FAILED) {
            return "FAILED";
        }
        if (result.getStatus() == ReviewResult.Status.SKIPPED) {
            return "SKIPPED";
        }
        if (extractBooleanMetric(metrics, "autoApprove.applied", false)) {
            return "APPROVED";
        }
        if (result.hasCriticalIssues()) {
            return "CHANGES_REQUESTED";
        }
        return "REVIEWED";
    }

    private String serializeMetrics(Map<String, Object> metrics) {
        StringBuilder sb = new StringBuilder();
        appendJsonValue(sb, metrics);
        return sb.toString();
    }

    private void appendJsonValue(StringBuilder sb, Object value) {
        if (value == null) {
            sb.append("null");
            return;
        }
        if (value instanceof Map) {
            sb.append('{');
            Map<?, ?> map = (Map<?, ?>) value;
            boolean first = true;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!(entry.getKey() instanceof String)) {
                    continue;
                }
                if (!first) {
                    sb.append(',');
                }
                first = false;
                sb.append('\"').append(escapeJson((String) entry.getKey())).append('\"').append(':');
                appendJsonValue(sb, entry.getValue());
            }
            sb.append('}');
            return;
        }
        if (value instanceof Iterable) {
            sb.append('[');
            boolean first = true;
            for (Object element : (Iterable<?>) value) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                appendJsonValue(sb, element);
            }
            sb.append(']');
            return;
        }
        if (value.getClass().isArray()) {
            sb.append('[');
            int length = java.lang.reflect.Array.getLength(value);
            for (int i = 0; i < length; i++) {
                if (i > 0) {
                    sb.append(',');
                }
                appendJsonValue(sb, java.lang.reflect.Array.get(value, i));
            }
            sb.append(']');
            return;
        }
        if (value instanceof Number || value instanceof Boolean) {
            sb.append(value.toString());
            return;
        }
        sb.append('\"').append(escapeJson(String.valueOf(value))).append('\"');
    }

    private String escapeJson(String input) {
        StringBuilder sb = new StringBuilder(input.length() + 16);
        for (char c : input.toCharArray()) {
            switch (c) {
                case '\\\\':
                    sb.append(\"\\\\\\\\\");
                    break;
                case '\\\"':
                    sb.append(\"\\\\\\\"\");
                    break;
                case '\\n':
                    sb.append(\"\\\\n\");
                    break;
                case '\\r':
                    sb.append(\"\\\\r\");
                    break;
                case '\\t':
                    sb.append(\"\\\\t\");
                    break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format(\"\\\\u%04x\", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        return sb.toString();
    }

    /**
     * Sanitizes log messages to prevent log injection attacks.
     */
    private String sanitizeLogMessage(String message) {
        if (message == null) return "null";
        return message.replaceAll("[\r\n\t]", "_");
    }

    private Map<String, Integer> analyzeFilterReasons(ReviewContext context,
                                                      Set<String> filesToReview,
                                                      List<String> filesWithoutHunks) {
        Map<String, Integer> reasonCounts = new LinkedHashMap<>();
        reasonCounts.put("allowedExtensions", 0);
        reasonCounts.put("ignorePatterns", 0);
        reasonCounts.put("ignorePaths", 0);
        reasonCounts.put("generated", 0);
        reasonCounts.put("tests", 0);
        reasonCounts.put("noExtension", 0);
        reasonCounts.put("noDiffHunks", 0);

        Set<String> allowedExtensions = context.getConfig().getReviewableExtensions().stream()
                .map(String::toLowerCase)
                .collect(Collectors.toSet());
        List<Pattern> ignorePatterns = context.getConfig().getIgnorePatterns().stream()
                .filter(s -> !s.trim().isEmpty())
                .map(this::globToPattern)
                .collect(Collectors.toList());
        List<String> ignorePaths = context.getConfig().getIgnorePaths().stream()
                .map(String::toLowerCase)
                .collect(Collectors.toList());

        boolean skipGenerated = context.getConfig().getProfile().isSkipGeneratedFiles();
        boolean reviewTests = context.getConfig().getProfile().isReviewTests();
        Set<String> filesWithoutHunksSet = new HashSet<>(filesWithoutHunks);

        context.getFileStats().keySet().forEach(file -> {
            if (filesToReview.contains(file) && !filesWithoutHunksSet.contains(file)) {
                return; // part of reviewable set with hunks
            }

            if (filesWithoutHunksSet.contains(file)) {
                reasonCounts.computeIfPresent("noDiffHunks", (k, v) -> v + 1);
                return;
            }

            String lower = file.toLowerCase();
            for (String path : ignorePaths) {
                if (!path.isEmpty() && lower.contains(path)) {
                    reasonCounts.computeIfPresent("ignorePaths", (k, v) -> v + 1);
                    return;
                }
            }

            for (Pattern pattern : ignorePatterns) {
                if (pattern.matcher(lower).matches()) {
                    reasonCounts.computeIfPresent("ignorePatterns", (k, v) -> v + 1);
                    return;
                }
            }

            if (skipGenerated && looksGenerated(file)) {
                reasonCounts.computeIfPresent("generated", (k, v) -> v + 1);
                return;
            }

            if (!reviewTests && looksLikeTestFile(file)) {
                reasonCounts.computeIfPresent("tests", (k, v) -> v + 1);
                return;
            }

            int idx = file.lastIndexOf('.');
            if (idx < 0) {
                reasonCounts.computeIfPresent("noExtension", (k, v) -> v + 1);
                return;
            }

            String ext = file.substring(idx + 1).toLowerCase();
            if (!allowedExtensions.isEmpty() && !allowedExtensions.contains(ext)) {
                reasonCounts.computeIfPresent("allowedExtensions", (k, v) -> v + 1);
                return;
            }
        });

        return reasonCounts;
    }

    private Pattern globToPattern(String glob) {
        String trimmed = glob.trim().toLowerCase();
        if (trimmed.isEmpty()) {
            return Pattern.compile("$^");
        }
        String regex = trimmed
                .replace(".", "\\.")
                .replace("*", ".*")
                .replace("?", ".");
        return Pattern.compile(regex);
    }

    private boolean looksGenerated(String file) {
        String lower = file.toLowerCase();
        return lower.contains("generated") || lower.endsWith(".g.dart") || lower.contains("build/");
    }

    private boolean looksLikeTestFile(String file) {
        String lower = file.toLowerCase();
        return TEST_KEYWORDS.stream().anyMatch(lower::contains);
    }

    /**
     * Helper class to store chunk processing results.
     */
}
