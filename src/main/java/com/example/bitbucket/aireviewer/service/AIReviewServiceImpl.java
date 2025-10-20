package com.example.bitbucket.aireviewer.service;

import com.atlassian.activeobjects.external.ActiveObjects;
import com.atlassian.bitbucket.comment.Comment;
import com.atlassian.bitbucket.comment.CommentService;
import com.atlassian.bitbucket.comment.AddCommentRequest;
import com.atlassian.bitbucket.pull.PullRequest;
import com.atlassian.bitbucket.pull.PullRequestService;
import com.atlassian.bitbucket.server.ApplicationPropertiesService;
import com.atlassian.plugin.spring.scanner.annotation.imports.ComponentImport;
import com.example.bitbucket.aireviewer.ao.AIReviewHistory;
import com.example.bitbucket.aireviewer.dto.ReviewIssue;
import com.example.bitbucket.aireviewer.dto.ReviewResult;
import com.example.bitbucket.aireviewer.util.*;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import javax.inject.Inject;
import javax.inject.Named;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Matcher;
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
    private static final Gson gson = new Gson();

    private final PullRequestService pullRequestService;
    private final CommentService commentService;
    private final AIReviewerConfigService configService;
    private final ActiveObjects ao;
    private final ApplicationPropertiesService applicationPropertiesService;

    @Inject
    public AIReviewServiceImpl(
            @ComponentImport PullRequestService pullRequestService,
            @ComponentImport CommentService commentService,
            @ComponentImport ActiveObjects ao,
            @ComponentImport ApplicationPropertiesService applicationPropertiesService,
            AIReviewerConfigService configService) {
        this.pullRequestService = Objects.requireNonNull(pullRequestService, "pullRequestService cannot be null");
        this.commentService = Objects.requireNonNull(commentService, "commentService cannot be null");
        this.ao = Objects.requireNonNull(ao, "activeObjects cannot be null");
        this.applicationPropertiesService = Objects.requireNonNull(applicationPropertiesService, "applicationPropertiesService cannot be null");
        this.configService = Objects.requireNonNull(configService, "configService cannot be null");
    }

    @Nonnull
    @Override
    public ReviewResult reviewPullRequest(@Nonnull PullRequest pullRequest) {
        long pullRequestId = pullRequest.getId();
        log.info("Starting AI review for pull request: {}", pullRequestId);
        MetricsCollector metrics = new MetricsCollector("pr-" + pullRequestId);
        Instant overallStart = metrics.recordStart("overall");

        try {
            PullRequest pr = pullRequest;
            log.info("Reviewing PR #{} in {}/{}: {}",
                    pr.getId(),
                    pr.getToRef().getRepository().getProject().getKey(),
                    pr.getToRef().getRepository().getSlug(),
                    pr.getTitle());

            // Fetch diff
            Instant fetchStart = metrics.recordStart("fetchDiff");
            String diffText = fetchDiff(pr);
            metrics.recordEnd("fetchDiff", fetchStart);

            if (diffText == null || diffText.trim().isEmpty()) {
                log.warn("No diff content found for PR #{}", pullRequestId);
                return ReviewResult.builder()
                        .pullRequestId(pullRequestId)
                        .status(ReviewResult.Status.SKIPPED)
                        .message("No changes to review")
                        .filesReviewed(0)
                        .filesSkipped(0)
                        .metrics(metrics.getMetrics())
                        .build();
            }

            // Validate PR size
            Instant validateStart = metrics.recordStart("validateSize");
            PRSizeValidation sizeCheck = validatePRSize(diffText);
            metrics.recordEnd("validateSize", validateStart);
            metrics.recordMetric("diffSizeMB", (long) sizeCheck.getSizeMB());
            metrics.recordMetric("diffLines", sizeCheck.getLines());

            if (!sizeCheck.isValid()) {
                log.error("PR #{} too large: {}", pullRequestId, sizeCheck.getMessage());
                return ReviewResult.builder()
                        .pullRequestId(pullRequestId)
                        .status(ReviewResult.Status.FAILED)
                        .message(sizeCheck.getMessage())
                        .filesReviewed(0)
                        .filesSkipped(0)
                        .metrics(metrics.getMetrics())
                        .build();
            }

            log.info("PR #{} size: {} lines, {:.2f} MB", pullRequestId, sizeCheck.getLines(), sizeCheck.getSizeMB());

            // Analyze diff for file changes
            Instant analyzeStart = metrics.recordStart("analyzeDiff");
            Map<String, FileChange> fileChanges = analyzeDiffForSummary(diffText);
            metrics.recordEnd("analyzeDiff", analyzeStart);
            metrics.recordMetric("totalFiles", fileChanges.size());

            log.info("PR #{} changes {} file(s)", pullRequestId, fileChanges.size());

            // Filter files for review
            Instant filterStart = metrics.recordStart("filterFiles");
            Set<String> filesToReview = filterFilesForReview(fileChanges.keySet());
            metrics.recordEnd("filterFiles", filterStart);
            metrics.recordMetric("filesToReview", filesToReview.size());
            metrics.recordMetric("filesSkipped", fileChanges.size() - filesToReview.size());

            log.info("PR #{} will review {} file(s), skipped {} file(s)",
                    pullRequestId, filesToReview.size(), fileChanges.size() - filesToReview.size());

            if (filesToReview.isEmpty()) {
                log.info("No files to review after filtering");
                return ReviewResult.builder()
                        .pullRequestId(pullRequestId)
                        .status(ReviewResult.Status.SUCCESS)
                        .message("No reviewable files found (all filtered)")
                        .filesReviewed(0)
                        .filesSkipped(fileChanges.size())
                        .metrics(metrics.getMetrics())
                        .build();
            }

            // Chunk diff
            Instant chunkStart = metrics.recordStart("chunkDiff");
            List<DiffChunk> chunks = smartChunkDiff(diffText, filesToReview);
            metrics.recordEnd("chunkDiff", chunkStart);
            metrics.recordMetric("chunks", chunks.size());

            log.info("PR #{} split into {} chunk(s) for processing", pullRequestId, chunks.size());
            chunks.forEach(chunk -> log.debug("  {}", chunk));

            // Process chunks with Ollama in parallel
            Instant ollamaStart = metrics.recordStart("ollamaProcessing");
            List<ReviewIssue> issues = processChunksInParallel(chunks, pr);
            metrics.recordEnd("ollamaProcessing", ollamaStart);
            metrics.recordMetric("totalIssues", issues.size());

            log.info("PR #{} analysis complete: {} issues found", pullRequestId, issues.size());

            // Count issues by severity
            long criticalCount = issues.stream().filter(i -> i.getSeverity() == ReviewIssue.Severity.CRITICAL).count();
            long highCount = issues.stream().filter(i -> i.getSeverity() == ReviewIssue.Severity.HIGH).count();
            long mediumCount = issues.stream().filter(i -> i.getSeverity() == ReviewIssue.Severity.MEDIUM).count();
            long lowCount = issues.stream().filter(i -> i.getSeverity() == ReviewIssue.Severity.LOW).count();

            metrics.recordMetric("criticalIssues", criticalCount);
            metrics.recordMetric("highIssues", highCount);
            metrics.recordMetric("mediumIssues", mediumCount);
            metrics.recordMetric("lowIssues", lowCount);

            log.info("Issue breakdown - Critical: {}, High: {}, Medium: {}, Low: {}",
                    criticalCount, highCount, mediumCount, lowCount);

            // Re-review comparison logic
            List<ReviewIssue> previousIssues = getPreviousIssues(pr);
            List<ReviewIssue> resolvedIssues = new ArrayList<>();
            List<ReviewIssue> newIssues = new ArrayList<>();

            if (!previousIssues.isEmpty()) {
                resolvedIssues = findResolvedIssues(previousIssues, issues);
                newIssues = findNewIssues(previousIssues, issues);
                log.info("Re-review comparison: {} resolved, {} new out of {} previous and {} current issues",
                        resolvedIssues.size(), newIssues.size(), previousIssues.size(), issues.size());
                metrics.recordMetric("resolvedIssues", resolvedIssues.size());
                metrics.recordMetric("newIssues", newIssues.size());
            } else {
                log.info("No previous review found - first review for this PR");
            }

            // Post comments to PR
            Instant commentStart = metrics.recordStart("postComments");
            int commentsPosted = 0;
            int failedChunks = 0; // Track from parallel processing if needed

            if (!issues.isEmpty()) {
                try {
                    // Calculate elapsed time for summary
                    long elapsedSeconds = java.time.Duration.between(overallStart, Instant.now()).getSeconds();

                    // Build and post summary comment (includes resolved/new issues if applicable)
                    String summaryText = buildSummaryComment(issues, fileChanges, pr, elapsedSeconds, failedChunks, resolvedIssues, newIssues);
                    Comment summaryComment = addPRComment(pr, summaryText);
                    log.info("Posted summary comment with ID: {}", summaryComment.getId());

                    // Post individual issue comments as replies
                    commentsPosted = postIssueComments(issues, summaryComment, pr);
                    log.info("Posted {} issue comment replies", commentsPosted);

                } catch (Exception e) {
                    log.error("Failed to post comments: {}", e.getMessage(), e);
                    // Continue even if comment posting fails - we still have the results
                }
            } else {
                log.info("No issues found - skipping comment posting");
            }

            metrics.recordEnd("postComments", commentStart);
            metrics.recordMetric("commentsPosted", commentsPosted);

            // PR Approval/Rejection Logic
            Map<String, Object> config = configService.getConfigurationAsMap();
            boolean approved = false;

            if (shouldApprovePR(issues, config)) {
                log.info("Attempting to auto-approve PR #{}", pullRequestId);
                approved = approvePR(pr);
                if (approved) {
                    log.info("✅ PR #{} auto-approved - no critical/high issues found", pullRequestId);
                    metrics.recordMetric("autoApproved", 1);
                } else {
                    log.warn("Failed to auto-approve PR #{}", pullRequestId);
                    metrics.recordMetric("autoApproveFailed", 1);
                }
            } else {
                log.info("PR #{} not auto-approved - critical/high issues present or auto-approve disabled", pullRequestId);
                metrics.recordMetric("autoApproved", 0);
            }

            // Determine status based on critical issues
            ReviewResult.Status status = criticalCount > 0 || highCount > 0
                    ? ReviewResult.Status.PARTIAL
                    : ReviewResult.Status.SUCCESS;

            String approvalStatus = approved ? " (auto-approved)" : "";
            String message = String.format("Review completed: %d issues found (%d critical, %d high, %d medium, %d low), %d comments posted%s",
                    issues.size(), criticalCount, highCount, mediumCount, lowCount, commentsPosted + (issues.isEmpty() ? 0 : 1), approvalStatus);

            ReviewResult result = ReviewResult.builder()
                    .pullRequestId(pullRequestId)
                    .status(status)
                    .message(message)
                    .issues(issues)
                    .filesReviewed(filesToReview.size())
                    .filesSkipped(fileChanges.size() - filesToReview.size())
                    .metrics(metrics.getMetrics())
                    .build();

            // Save review history to database
            saveReviewHistory(pr, issues, result);

            return result;

        } catch (Exception e) {
            log.error("Failed to review pull request: {}", pullRequestId, e);
            metrics.setGauge("error", e.getMessage());
            return ReviewResult.builder()
                    .pullRequestId(pullRequestId)
                    .status(ReviewResult.Status.FAILED)
                    .message("Review failed: " + e.getMessage())
                    .filesReviewed(0)
                    .filesSkipped(0)
                    .metrics(metrics.getMetrics())
                    .build();
        } finally {
            metrics.logMetrics();
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
     * Fetches the diff for a pull request.
     *
     * @param pullRequest the pull request
     * @return the diff as a string
     */
    @Nonnull
    private String fetchDiff(@Nonnull PullRequest pullRequest) {
        try {
            String baseUrl = applicationPropertiesService.getBaseUrl().toString();
            String project = pullRequest.getToRef().getRepository().getProject().getKey();
            String slug = pullRequest.getToRef().getRepository().getSlug();
            long prId = pullRequest.getId();

            String url = String.format("%s/rest/api/1.0/projects/%s/repos/%s/pull-requests/%d/diff?withComments=false&whitespace=ignore-all",
                    baseUrl, project, slug, prId);

            log.debug("Fetching diff from: {}", url);

            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            try {
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(30000);

                int responseCode = conn.getResponseCode();
                if (responseCode >= 400) {
                    String error = readErrorStream(conn);
                    throw new RuntimeException("HTTP " + responseCode + ": " + error);
                }

                String diff = readInputStream(conn);
                log.debug("Fetched diff: {} characters", diff.length());
                return diff;

            } finally {
                conn.disconnect();
            }

        } catch (Exception e) {
            log.error("Failed to fetch diff for PR #{}", pullRequest.getId(), e);
            throw new RuntimeException("Failed to fetch diff: " + e.getMessage(), e);
        }
    }

    /**
     * Reads input stream from HTTP connection.
     */
    private String readInputStream(HttpURLConnection conn) throws Exception {
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
            return br.lines().collect(Collectors.joining("\n"));
        }
    }

    /**
     * Reads error stream from HTTP connection.
     */
    private String readErrorStream(HttpURLConnection conn) {
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(conn.getErrorStream(), StandardCharsets.UTF_8))) {
            return br.lines().collect(Collectors.joining("\n"));
        } catch (Exception e) {
            return "Unable to read error stream: " + e.getMessage();
        }
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
                // Save previous file stats
                if (currentFile != null) {
                    fileChanges.put(currentFile, new FileChange(currentFile, additions, deletions));
                }

                // Extract new file path: "diff --git a/path/file.java b/path/file.java"
                String[] parts = line.split(" ");
                if (parts.length >= 3) {
                    currentFile = parts[2].substring(2); // Remove "a/" prefix
                    additions = 0;
                    deletions = 0;
                }
            } else if (line.startsWith("+") && !line.startsWith("+++")) {
                additions++;
            } else if (line.startsWith("-") && !line.startsWith("---")) {
                deletions++;
            }
        }

        // Save last file
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
     * Chunks a large diff into smaller pieces for processing.
     *
     * @param diffText the diff to chunk
     * @param filesToReview set of files to include
     * @return list of diff chunks
     */
    @Nonnull
    private List<DiffChunk> smartChunkDiff(@Nonnull String diffText, @Nonnull Set<String> filesToReview) {
        Map<String, Object> config = configService.getConfigurationAsMap();
        int maxCharsPerChunk = (int) config.get("maxCharsPerChunk");
        int maxFilesPerChunk = (int) config.get("maxFilesPerChunk");
        int maxChunks = (int) config.get("maxChunks");

        List<DiffChunk> chunks = new ArrayList<>();
        DiffChunk.Builder currentChunk = DiffChunk.builder().index(0);
        StringBuilder currentContent = new StringBuilder();
        List<String> currentFiles = new ArrayList<>();

        // Split diff by file
        String[] lines = diffText.split("\n");
        StringBuilder currentFileDiff = new StringBuilder();
        String currentFile = null;

        for (String line : lines) {
            if (line.startsWith("diff --git ")) {
                // Save previous file
                if (currentFile != null && filesToReview.contains(currentFile)) {
                    String fileDiff = currentFileDiff.toString();

                    // Check if adding this file exceeds limits
                    if ((currentContent.length() + fileDiff.length() > maxCharsPerChunk)
                            || (currentFiles.size() >= maxFilesPerChunk)) {
                        // Finalize current chunk
                        if (currentContent.length() > 0) {
                            chunks.add(currentChunk
                                    .content(currentContent.toString())
                                    .files(currentFiles)
                                    .build());

                            // Start new chunk
                            currentChunk = DiffChunk.builder().index(chunks.size());
                            currentContent = new StringBuilder();
                            currentFiles = new ArrayList<>();
                        }
                    }

                    // Add file to current chunk
                    currentContent.append(fileDiff);
                    currentFiles.add(currentFile);
                }

                // Start new file
                String[] parts = line.split(" ");
                if (parts.length >= 3) {
                    currentFile = parts[2].substring(2);  // Remove "a/" prefix
                }
                currentFileDiff = new StringBuilder();
                currentFileDiff.append(line).append("\n");
            } else {
                currentFileDiff.append(line).append("\n");
            }
        }

        // Save last file
        if (currentFile != null && filesToReview.contains(currentFile)) {
            String fileDiff = currentFileDiff.toString();
            if (currentContent.length() + fileDiff.length() > maxCharsPerChunk
                    || currentFiles.size() >= maxFilesPerChunk) {
                if (currentContent.length() > 0) {
                    chunks.add(currentChunk
                            .content(currentContent.toString())
                            .files(currentFiles)
                            .build());

                    currentChunk = DiffChunk.builder().index(chunks.size());
                    currentContent = new StringBuilder();
                    currentFiles = new ArrayList<>();
                }
            }
            currentContent.append(fileDiff);
            currentFiles.add(currentFile);
        }

        // Save last chunk
        if (currentContent.length() > 0) {
            chunks.add(currentChunk
                    .content(currentContent.toString())
                    .files(currentFiles)
                    .build());
        }

        // Limit to maxChunks
        if (chunks.size() > maxChunks) {
            log.warn("Diff produces {} chunks, limiting to {}", chunks.size(), maxChunks);
            chunks = chunks.subList(0, maxChunks);
        }

        return chunks;
    }

    /**
     * Builds the prompt for Ollama code review.
     *
     * @param diffChunk the diff chunk to review
     * @param pullRequest the pull request being reviewed
     * @param chunkIndex current chunk index (1-based)
     * @param totalChunks total number of chunks
     * @return JSON request body as string
     */
    @Nonnull
    private String buildPrompt(@Nonnull DiffChunk diffChunk, @Nonnull PullRequest pullRequest,
                                int chunkIndex, int totalChunks) {
        String project = pullRequest.getToRef().getRepository().getProject().getKey();
        String slug = pullRequest.getToRef().getRepository().getSlug();
        long prId = pullRequest.getId();

        String systemPrompt = "You are an expert senior software engineer performing a comprehensive code review. " +
                "ONLY analyze NEW or MODIFIED code, marked with '+' prefix in the diff.\n\n" +
                "⚠️ CRITICAL RULES:\n" +
                "1. ONLY analyze lines starting with '+' (new/modified code)\n" +
                "2. COMPLETELY IGNORE lines with '-' or ' ' prefix (old/unchanged code)\n" +
                "3. NEVER report issues in unchanged code sections\n" +
                "4. ONLY use EXACT file paths from the provided diff\n" +
                "5. VERIFY line numbers correspond to the new file version\n\n" +
                "CRITICAL ANALYSIS AREAS:\n" +
                "🔴 SECURITY: SQL injection, XSS, CSRF, authentication bypass, authorization flaws, input validation, data exposure, cryptographic issues\n" +
                "🔴 BUGS & LOGIC: Null pointer exceptions, array bounds, race conditions, deadlocks, infinite loops, incorrect algorithms, edge cases\n" +
                "🔴 PERFORMANCE: Memory leaks, inefficient queries, O(n²) algorithms, unnecessary computations, resource exhaustion\n" +
                "🔴 RELIABILITY: Error handling, exception management, transaction integrity, data consistency, retry logic\n" +
                "🔴 MAINTAINABILITY: Code complexity, duplicated logic, tight coupling, missing documentation, unclear naming\n" +
                "🔴 TESTING: Missing test coverage, inadequate assertions, test data issues, mock problems\n\n" +
                "SEVERITY GUIDELINES:\n" +
                "- CRITICAL: Security vulnerabilities, data corruption, system crashes, production outages\n" +
                "- HIGH: Logic errors, performance bottlenecks, reliability issues, significant bugs\n" +
                "- MEDIUM: Code quality issues, maintainability problems, minor performance issues\n" +
                "- LOW: Style improvements, documentation gaps, minor optimizations\n\n" +
                "BE THOROUGH - even small changes can introduce significant issues.";

        String userPrompt = String.format("COMPREHENSIVE CODE REVIEW REQUEST\n\n" +
                "Repository: %s/%s\n" +
                "Pull Request: #%d\n" +
                "Analyzing chunk %d of %d\n\n" +
                "PERFORM DETAILED LINE-BY-LINE ANALYSIS:\n\n" +
                "===DIFF START===\n%s\n===DIFF END===\n\n" +
                "AVAILABLE FILES IN THIS DIFF:\n%s\n\n" +
                "You MUST only report issues for files listed above. Do not invent or guess file paths.\n\n" +
                "CRITICAL INSTRUCTIONS:\n" +
                "1. ONLY analyze lines that start with '+' (additions) - these are the NEW/CHANGED code\n" +
                "2. IGNORE lines that start with '-' (deletions) or ' ' (context) - these are old/unchanged code\n" +
                "3. Focus exclusively on potential issues in the ADDED/MODIFIED lines\n" +
                "4. Report line numbers based on the NEW file version (after changes)\n" +
                "5. Consider the broader context but only flag issues in the changed code\n" +
                "6. Look for subtle bugs that could cause runtime failures in NEW code\n" +
                "7. Identify security vulnerabilities in ADDED lines\n" +
                "8. Check for performance implications of NEW changes\n\n" +
                "IMPORTANT FILE PATH RULES:\n" +
                "- ONLY use file paths that appear EXACTLY in the diff above\n" +
                "- DO NOT invent, guess, or modify file paths\n" +
                "- DO NOT use similar or related file paths\n\n" +
                "Provide detailed findings in JSON format with EXACT file paths from the diff and specific line numbers for CHANGED CODE ONLY.\n\n" +
                "For each issue, include the 'problematicCode' field with the EXACT code snippet that has the problem (copy it exactly from the diff above).",
                project, slug, prId, chunkIndex, totalChunks,
                diffChunk.getContent(),
                String.join("\n", diffChunk.getFiles()));

        // Build JSON request
        JsonObject request = new JsonObject();
        Map<String, Object> config = configService.getConfigurationAsMap();
        String model = (String) config.get("ollamaModel");

        request.addProperty("model", model);
        request.addProperty("stream", false);

        // JSON schema for structured output
        JsonObject format = new JsonObject();
        format.addProperty("type", "object");

        JsonObject properties = new JsonObject();
        JsonObject issuesProperty = new JsonObject();
        issuesProperty.addProperty("type", "array");

        JsonObject items = new JsonObject();
        items.addProperty("type", "object");

        JsonObject itemProperties = new JsonObject();
        itemProperties.add("path", createJsonType("string"));
        itemProperties.add("line", createJsonType("integer"));

        JsonObject severityType = createJsonType("string");
        JsonArray severityEnum = new JsonArray();
        severityEnum.add(new JsonPrimitive("low"));
        severityEnum.add(new JsonPrimitive("medium"));
        severityEnum.add(new JsonPrimitive("high"));
        severityEnum.add(new JsonPrimitive("critical"));
        severityType.add("enum", severityEnum);
        itemProperties.add("severity", severityType);

        itemProperties.add("type", createJsonType("string"));
        itemProperties.add("summary", createJsonType("string"));
        itemProperties.add("details", createJsonType("string"));
        itemProperties.add("fix", createJsonType("string"));
        itemProperties.add("problematicCode", createJsonType("string"));

        items.add("properties", itemProperties);

        JsonArray required = new JsonArray();
        required.add(new JsonPrimitive("path"));
        required.add(new JsonPrimitive("line"));
        required.add(new JsonPrimitive("summary"));
        items.add("required", required);

        issuesProperty.add("items", items);
        properties.add("issues", issuesProperty);

        format.add("properties", properties);

        JsonArray formatRequired = new JsonArray();
        formatRequired.add(new JsonPrimitive("issues"));
        format.add("required", formatRequired);

        request.add("format", format);

        // Messages
        JsonArray messages = new JsonArray();

        JsonObject systemMessage = new JsonObject();
        systemMessage.addProperty("role", "system");
        systemMessage.addProperty("content", systemPrompt);
        messages.add(systemMessage);

        JsonObject userMessage = new JsonObject();
        userMessage.addProperty("role", "user");
        userMessage.addProperty("content", userPrompt);
        messages.add(userMessage);

        request.add("messages", messages);

        return gson.toJson(request);
    }

    /**
     * Helper method to create JSON type object.
     */
    private JsonObject createJsonType(String type) {
        JsonObject obj = new JsonObject();
        obj.addProperty("type", type);
        return obj;
    }

    /**
     * Calls Ollama API to analyze a code chunk.
     *
     * @param diffChunk the diff chunk to analyze
     * @param pullRequest the pull request
     * @param chunkIndex current chunk index (1-based)
     * @param totalChunks total chunks
     * @param model the model to use
     * @return list of ReviewIssue objects
     */
    @Nonnull
    private List<ReviewIssue> callOllama(@Nonnull DiffChunk diffChunk, @Nonnull PullRequest pullRequest,
                                          int chunkIndex, int totalChunks, @Nonnull String model) {
        Map<String, Object> config = configService.getConfigurationAsMap();
        String ollamaUrl = (String) config.get("ollamaUrl");
        int ollamaTimeout = (int) config.get("ollamaTimeout");

        log.info("Calling Ollama for chunk {}/{} with model {}", chunkIndex, totalChunks, model);

        try {
            // Build prompt
            String requestBody = buildPrompt(diffChunk, pullRequest, chunkIndex, totalChunks);

            // Call Ollama API
            URL url = new URL(ollamaUrl + "/api/chat");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            try {
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(ollamaTimeout);
                conn.setDoOutput(true);

                // Write request
                try (OutputStream os = conn.getOutputStream()) {
                    byte[] input = requestBody.getBytes(StandardCharsets.UTF_8);
                    os.write(input, 0, input.length);
                }

                int responseCode = conn.getResponseCode();
                if (responseCode >= 400) {
                    String error = readErrorStream(conn);
                    throw new RuntimeException("Ollama API error (HTTP " + responseCode + "): " + error);
                }

                // Read response
                String responseBody = readInputStream(conn);
                log.debug("Ollama response: {} chars", responseBody.length());

                // Parse response
                return parseOllamaResponse(responseBody, diffChunk);

            } finally {
                conn.disconnect();
            }

        } catch (Exception e) {
            log.error("Failed to call Ollama for chunk {}/{}: {}", chunkIndex, totalChunks, e.getMessage(), e);
            throw new RuntimeException("Ollama API call failed: " + e.getMessage(), e);
        }
    }

    /**
     * Parses Ollama API response and extracts issues.
     *
     * @param responseBody the raw JSON response from Ollama
     * @param diffChunk the diff chunk that was analyzed
     * @return list of valid ReviewIssue objects
     */
    @Nonnull
    private List<ReviewIssue> parseOllamaResponse(@Nonnull String responseBody, @Nonnull DiffChunk diffChunk) {
        try {
            JsonObject response = gson.fromJson(responseBody, JsonObject.class);

            if (!response.has("message")) {
                log.warn("No message in Ollama response");
                return Collections.emptyList();
            }

            JsonObject message = response.getAsJsonObject("message");
            if (!message.has("content")) {
                log.warn("No content in Ollama message");
                return Collections.emptyList();
            }

            String content = message.get("content").getAsString();
            if (content == null || content.trim().isEmpty()) {
                log.warn("Empty content in Ollama response");
                return Collections.emptyList();
            }

            // Parse the content as JSON (Ollama returns JSON inside the content field)
            JsonObject contentJson = gson.fromJson(content, JsonObject.class);

            if (!contentJson.has("issues")) {
                log.warn("No issues array in Ollama response");
                return Collections.emptyList();
            }

            JsonArray issuesArray = contentJson.getAsJsonArray("issues");
            log.info("Ollama returned {} raw issues", issuesArray.size());

            List<ReviewIssue> validIssues = new ArrayList<>();
            Set<String> validFiles = new HashSet<>(diffChunk.getFiles());

            for (JsonElement issueElement : issuesArray) {
                try {
                    JsonObject issueObj = issueElement.getAsJsonObject();

                    // Validate file path
                    if (!issueObj.has("path")) {
                        log.debug("Skipping issue without path");
                        continue;
                    }

                    String path = issueObj.get("path").getAsString();
                    if (!validFiles.contains(path)) {
                        log.debug("Skipping issue for invalid file path: {}", path);
                        continue;
                    }

                    // Validate line number
                    if (!issueObj.has("line")) {
                        log.debug("Skipping issue without line number");
                        continue;
                    }

                    int line = issueObj.get("line").getAsInt();
                    if (line <= 0) {
                        log.debug("Skipping issue with invalid line number: {}", line);
                        continue;
                    }

                    // Validate summary
                    if (!issueObj.has("summary")) {
                        log.debug("Skipping issue without summary");
                        continue;
                    }

                    String summary = issueObj.get("summary").getAsString();
                    if (summary == null || summary.trim().isEmpty()) {
                        log.debug("Skipping issue with empty summary");
                        continue;
                    }

                    // Extract optional fields
                    String severity = issueObj.has("severity") ? issueObj.get("severity").getAsString() : "medium";
                    String type = issueObj.has("type") ? issueObj.get("type").getAsString() : "code-quality";
                    String details = issueObj.has("details") ? issueObj.get("details").getAsString() : "";
                    String fix = issueObj.has("fix") ? issueObj.get("fix").getAsString() : "";
                    String problematicCode = issueObj.has("problematicCode") ? issueObj.get("problematicCode").getAsString() : "";

                    // Create ReviewIssue
                    ReviewIssue issue = ReviewIssue.builder()
                            .path(path)
                            .line(line)
                            .severity(mapSeverity(severity))
                            .type(type)
                            .summary(summary)
                            .details(details)
                            .fix(fix)
                            .problematicCode(problematicCode)
                            .build();

                    validIssues.add(issue);

                } catch (Exception e) {
                    log.warn("Failed to parse individual issue: {}", e.getMessage());
                }
            }

            log.info("Parsed {} valid issues from {} raw issues", validIssues.size(), issuesArray.size());
            return validIssues;

        } catch (Exception e) {
            log.error("Failed to parse Ollama response: {}", e.getMessage(), e);
            return Collections.emptyList();
        }
    }

    /**
     * Maps severity string to ReviewIssue.Severity enum.
     */
    private ReviewIssue.Severity mapSeverity(String severity) {
        if (severity == null) {
            return ReviewIssue.Severity.MEDIUM;
        }

        switch (severity.toLowerCase()) {
            case "critical":
                return ReviewIssue.Severity.CRITICAL;
            case "high":
                return ReviewIssue.Severity.HIGH;
            case "medium":
                return ReviewIssue.Severity.MEDIUM;
            case "low":
                return ReviewIssue.Severity.LOW;
            case "info":
                return ReviewIssue.Severity.INFO;
            default:
                return ReviewIssue.Severity.MEDIUM;
        }
    }

    /**
     * Robust Ollama call with retry logic and fallback model.
     *
     * @param diffChunk the diff chunk
     * @param pullRequest the pull request
     * @param chunkIndex chunk index
     * @param totalChunks total chunks
     * @return list of issues
     */
    @Nonnull
    private List<ReviewIssue> robustOllamaCall(@Nonnull DiffChunk diffChunk, @Nonnull PullRequest pullRequest,
                                                int chunkIndex, int totalChunks) {
        Map<String, Object> config = configService.getConfigurationAsMap();
        String primaryModel = (String) config.get("ollamaModel");
        String fallbackModel = (String) config.get("fallbackModel");
        int maxRetries = (int) config.get("maxRetries");
        int baseRetryDelay = (int) config.get("baseRetryDelayMs");

        List<ReviewIssue> issues = null;
        Exception lastException = null;

        // Try primary model with retries
        for (int attempt = 0; attempt < maxRetries; attempt++) {
            try {
                log.info("Attempt {}/{} with primary model: {}", attempt + 1, maxRetries, primaryModel);
                issues = callOllama(diffChunk, pullRequest, chunkIndex, totalChunks, primaryModel);

                if (issues != null && !issues.isEmpty()) {
                    log.info("Primary model succeeded with {} issues", issues.size());
                    return issues;
                }

                log.warn("Primary model returned no issues");

            } catch (Exception e) {
                lastException = e;
                log.warn("Attempt {}/{} failed with primary model: {}", attempt + 1, maxRetries, e.getMessage());

                if (attempt < maxRetries - 1) {
                    int delay = (int) (Math.pow(2, attempt) * baseRetryDelay);
                    log.info("Retrying in {}ms...", delay);
                    try {
                        Thread.sleep(delay);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }

        // Try fallback model if available and different from primary
        if (fallbackModel != null && !fallbackModel.equals(primaryModel)) {
            log.info("Trying fallback model: {}", fallbackModel);
            try {
                issues = callOllama(diffChunk, pullRequest, chunkIndex, totalChunks, fallbackModel);

                if (issues != null && !issues.isEmpty()) {
                    log.info("Fallback model succeeded with {} issues", issues.size());
                    return issues;
                }

                log.warn("Fallback model returned no issues");

            } catch (Exception e) {
                log.error("Fallback model failed: {}", e.getMessage());
                lastException = e;
            }
        }

        // All attempts failed
        if (lastException != null) {
            throw new RuntimeException("All Ollama attempts failed: " + lastException.getMessage(), lastException);
        }

        // No exception but no issues either - return empty list
        log.warn("No issues found after all attempts");
        return Collections.emptyList();
    }

    /**
     * Processes multiple chunks in parallel using a thread pool.
     *
     * @param chunks list of diff chunks
     * @param pullRequest the pull request
     * @return list of all issues from all chunks
     */
    @Nonnull
    private List<ReviewIssue> processChunksInParallel(@Nonnull List<DiffChunk> chunks, @Nonnull PullRequest pullRequest) {
        Map<String, Object> config = configService.getConfigurationAsMap();
        int parallelThreads = (int) config.get("parallelChunkThreads");

        int threadCount = Math.min(parallelThreads, chunks.size());
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);

        log.info("Processing {} chunks in parallel with {} threads", chunks.size(), threadCount);

        List<Future<ChunkResult>> futures = new ArrayList<>();

        try {
            // Submit all chunks for processing
            for (int i = 0; i < chunks.size(); i++) {
                final int chunkIndex = i;
                final DiffChunk chunk = chunks.get(i);

                Future<ChunkResult> future = executor.submit(() -> {
                    Instant startTime = Instant.now();
                    try {
                        log.info("Processing chunk {}/{}", chunkIndex + 1, chunks.size());
                        List<ReviewIssue> issues = robustOllamaCall(chunk, pullRequest, chunkIndex + 1, chunks.size());
                        long elapsed = java.time.Duration.between(startTime, Instant.now()).toMillis();

                        log.info("Chunk {}/{} completed in {}ms with {} issues",
                                chunkIndex + 1, chunks.size(), elapsed, issues.size());

                        return new ChunkResult(chunkIndex, true, issues, null, elapsed);

                    } catch (Exception e) {
                        long elapsed = java.time.Duration.between(startTime, Instant.now()).toMillis();
                        log.error("Chunk {}/{} failed in {}ms: {}",
                                chunkIndex + 1, chunks.size(), elapsed, e.getMessage());

                        return new ChunkResult(chunkIndex, false, null, e.getMessage(), elapsed);
                    }
                });

                futures.add(future);
            }

            // Collect results
            List<ReviewIssue> allIssues = new ArrayList<>();
            int successCount = 0;
            int failureCount = 0;

            for (int i = 0; i < futures.size(); i++) {
                try {
                    ChunkResult result = futures.get(i).get(5, TimeUnit.MINUTES);

                    if (result.success && result.issues != null) {
                        allIssues.addAll(result.issues);
                        successCount++;
                    } else {
                        failureCount++;
                        log.error("Chunk {} failed: {}", result.index + 1, result.error);
                    }

                } catch (TimeoutException e) {
                    failureCount++;
                    log.error("Chunk {} timed out after 5 minutes", i + 1);
                } catch (Exception e) {
                    failureCount++;
                    log.error("Failed to get result for chunk {}: {}", i + 1, e.getMessage());
                }
            }

            log.info("Parallel processing complete: {} successful, {} failed, {} total issues",
                    successCount, failureCount, allIssues.size());

            return allIssues;

        } finally {
            executor.shutdown();
            try {
                if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                    executor.shutdownNow();
                }
            } catch (InterruptedException e) {
                executor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
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
        int maxIssuesPerFile = 5; // Limit displayed issues per file in summary

        StringBuilder md = new StringBuilder();

        // Count by severity
        long criticalCount = issues.stream().filter(i -> i.getSeverity() == ReviewIssue.Severity.CRITICAL).count();
        long highCount = issues.stream().filter(i -> i.getSeverity() == ReviewIssue.Severity.HIGH).count();
        long mediumCount = issues.stream().filter(i -> i.getSeverity() == ReviewIssue.Severity.MEDIUM).count();
        long lowCount = issues.stream().filter(i -> i.getSeverity() == ReviewIssue.Severity.LOW).count();

        // Header based on severity
        if (criticalCount > 0 || highCount > 0) {
            md.append("🚫 **AI Code Review** – ").append(issues.size())
              .append(" finding(s) - **CHANGES REQUIRED**\n\n");
            md.append("> ⚠️ This PR has **").append(criticalCount + highCount)
              .append(" critical/high severity issue(s)** that must be addressed before merging.\n\n");
        } else {
            md.append("⚠️ **AI Code Review** – ").append(issues.size())
              .append(" finding(s) - **Review Recommended**\n\n");
            md.append("> ℹ️ This PR has only medium/low severity issues. You may merge after review, but consider addressing these improvements.\n\n");
        }

        // Summary table
        md.append("### Summary\n");
        md.append("| Severity | Count |\n");
        md.append("|----------|-------|\n");
        if (criticalCount > 0) md.append("| 🔴 Critical | ").append(criticalCount).append(" |\n");
        if (highCount > 0) md.append("| 🟠 High | ").append(highCount).append(" |\n");
        if (mediumCount > 0) md.append("| 🟡 Medium | ").append(mediumCount).append(" |\n");
        if (lowCount > 0) md.append("| 🔵 Low | ").append(lowCount).append(" |\n");
        md.append("\n");

        // File changes table
        md.append("### 📁 File-Level Changes\n\n");
        if (!fileChanges.isEmpty()) {
            // Group issues by file
            Map<String, List<ReviewIssue>> issuesByFile = issues.stream()
                    .collect(Collectors.groupingBy(ReviewIssue::getPath));

            // Sort files by total changes
            List<Map.Entry<String, FileChange>> sortedFiles = fileChanges.entrySet().stream()
                    .sorted((a, b) -> Integer.compare(
                            b.getValue().getTotalChanges(),
                            a.getValue().getTotalChanges()))
                    .collect(Collectors.toList());

            md.append("| File | +Added | -Deleted | Issues |\n");
            md.append("|------|--------|----------|--------|\n");

            int filesShown = Math.min(20, sortedFiles.size());
            for (int i = 0; i < filesShown; i++) {
                Map.Entry<String, FileChange> entry = sortedFiles.get(i);
                String fileName = entry.getKey();
                FileChange stats = entry.getValue();
                int issuesInFile = issuesByFile.getOrDefault(fileName, Collections.emptyList()).size();
                String issueIcon = issuesInFile > 0 ? "⚠️" : "✓";

                md.append("| `").append(fileName).append("` | +")
                  .append(stats.getAdditions()).append(" | -")
                  .append(stats.getDeletions()).append(" | ")
                  .append(issueIcon).append(" ").append(issuesInFile).append(" |\n");
            }

            if (sortedFiles.size() > 20) {
                md.append("| _...and ").append(sortedFiles.size() - 20).append(" more files_ | | | |\n");
            }

            md.append("\n");

            int totalAdditions = fileChanges.values().stream().mapToInt(FileChange::getAdditions).sum();
            int totalDeletions = fileChanges.values().stream().mapToInt(FileChange::getDeletions).sum();
            md.append("**Total Changes:** +").append(totalAdditions)
              .append(" additions, -").append(totalDeletions)
              .append(" deletions across ").append(fileChanges.size()).append(" file(s)\n\n");
        } else {
            md.append("_No file change statistics available_\n\n");
        }

        // Issues by file
        md.append("### Issues by File\n\n");
        Map<String, List<ReviewIssue>> issuesByFile = issues.stream()
                .collect(Collectors.groupingBy(ReviewIssue::getPath));

        int filesWithIssuesShown = 0;
        for (Map.Entry<String, List<ReviewIssue>> entry : issuesByFile.entrySet()) {
            if (filesWithIssuesShown >= 10) break;

            String file = entry.getKey();
            List<ReviewIssue> fileIssues = entry.getValue();

            md.append("#### `").append(file).append("`\n");
            int issuesShown = Math.min(maxIssuesPerFile, fileIssues.size());
            for (int i = 0; i < issuesShown; i++) {
                ReviewIssue issue = fileIssues.get(i);
                String icon = getSeverityIcon(issue.getSeverity());
                String loc = issue.getLine() > 0 ? "L" + issue.getLine() : "";

                md.append("- ").append(icon).append(" **")
                  .append(issue.getSeverity().name()).append("** ")
                  .append(loc).append(" — *").append(issue.getType())
                  .append("*: ").append(issue.getSummary()).append("\n");

                if (issue.getDetails() != null && issue.getDetails().length() < 200) {
                    md.append("  \n  ").append(issue.getDetails()).append("\n");
                }
            }

            if (fileIssues.size() > maxIssuesPerFile) {
                md.append("\n_...and ").append(fileIssues.size() - maxIssuesPerFile)
                  .append(" more issue(s) in this file._\n");
            }
            md.append("\n");
            filesWithIssuesShown++;
        }

        if (issuesByFile.size() > 10) {
            md.append("_...and issues in ").append(issuesByFile.size() - 10).append(" more file(s)._\n\n");
        }

        // Footer
        // Re-review comparison (if applicable)
        if (!resolvedIssues.isEmpty() || !newIssues.isEmpty()) {
            md.append("---\n");
            md.append("### 🔄 Changes Since Last Review\n\n");

            if (!resolvedIssues.isEmpty()) {
                md.append("✅ **").append(resolvedIssues.size()).append(" issue(s) resolved:**\n");
                for (int i = 0; i < Math.min(5, resolvedIssues.size()); i++) {
                    ReviewIssue issue = resolvedIssues.get(i);
                    md.append("- ✓ `").append(issue.getPath()).append(":").append(issue.getLine())
                      .append("` - ").append(issue.getSummary().substring(0, Math.min(60, issue.getSummary().length())))
                      .append(issue.getSummary().length() > 60 ? "..." : "").append("\n");
                }
                if (resolvedIssues.size() > 5) {
                    md.append("- _...and ").append(resolvedIssues.size() - 5).append(" more_\n");
                }
                md.append("\n");
            }

            if (!newIssues.isEmpty()) {
                md.append("🆕 **").append(newIssues.size()).append(" new issue(s) introduced:**\n");
                for (int i = 0; i < Math.min(5, newIssues.size()); i++) {
                    ReviewIssue issue = newIssues.get(i);
                    String icon = getSeverityIcon(issue.getSeverity());
                    md.append("- ").append(icon).append(" `").append(issue.getPath()).append(":").append(issue.getLine())
                      .append("` - ").append(issue.getSummary().substring(0, Math.min(60, issue.getSummary().length())))
                      .append(issue.getSummary().length() > 60 ? "..." : "").append("\n");
                }
                if (newIssues.size() > 5) {
                    md.append("- _...and ").append(newIssues.size() - 5).append(" more_\n");
                }
                md.append("\n");
            }
        }

        md.append("---\n");
        md.append("_Model: ").append(model).append(" • Analysis time: ")
          .append(elapsedSeconds).append("s_\n\n");

        if (criticalCount > 0 || highCount > 0) {
            md.append("**🚫 PR Status:** Changes required before merge (")
              .append(criticalCount).append(" critical, ")
              .append(highCount).append(" high severity)\n\n");
        } else {
            md.append("**✅ PR Status:** May merge after review (only medium/low issues)\n\n");
        }

        if (failedChunks > 0) {
            md.append("⚠️ **Warning**: ").append(failedChunks)
              .append(" chunk(s) failed to analyze - some issues may be missing.\n\n");
        }

        md.append("📝 **Detailed AI-generated explanations** will be posted as replies to this comment.\n");

        return md.toString();
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
            log.error("Failed to post PR comment: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to post PR comment: " + e.getMessage(), e);
        }
    }

    /**
     * Posts individual issue comments as separate PR comments.
     * Note: Bitbucket CommentService doesn't support parent/reply in AddCommentRequest.Builder
     * so we post as regular comments with clear headers indicating they're part of the review.
     *
     * @param issues all issues to post
     * @param pullRequest the pull request
     * @return number of comments successfully posted
     */
    private int postIssueComments(@Nonnull List<ReviewIssue> issues,
                                   @Nonnull Comment summaryComment,
                                   @Nonnull PullRequest pullRequest) {
        Map<String, Object> config = configService.getConfigurationAsMap();
        int maxIssueComments = 20; // Limit to prevent comment spam
        int apiDelayMs = (int) config.get("apiDelayMs");

        List<ReviewIssue> issuesToPost = issues.stream()
                .limit(maxIssueComments)
                .collect(Collectors.toList());

        int commentsCreated = 0;
        int commentsFailed = 0;

        for (int i = 0; i < issuesToPost.size(); i++) {
            ReviewIssue issue = issuesToPost.get(i);

            try {
                String commentText = buildIssueComment(issue, i + 1, issues.size());
                AddCommentRequest request = new AddCommentRequest.Builder(pullRequest, commentText).build();

                Comment comment = commentService.addComment(request);
                log.info("Posted issue comment {} with ID {}", i + 1, comment.getId());
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
                log.error("Failed to post issue comment {}/{}: {}", i + 1, issuesToPost.size(), e.getMessage());
                commentsFailed++;
            }
        }

        log.info("Posted {}/{} issue comments ({} failed)", commentsCreated, issuesToPost.size(), commentsFailed);
        return commentsCreated;
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
        if (issue.getLine() > 0) {
            md.append(" **(Line ").append(issue.getLine()).append(")**");
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
     * Determines whether the PR should be auto-approved based on issue severity.
     * Approves if autoApprove is enabled and there are no critical or high severity issues.
     *
     * @param issues list of all issues found
     * @param config configuration map
     * @return true if PR should be approved
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
     * Approves the pull request via Bitbucket REST API.
     * Uses HTTP POST to /rest/api/1.0/projects/{project}/repos/{repo}/pull-requests/{prId}/approve
     *
     * @param pullRequest the pull request to approve
     * @return true if approval succeeded, false otherwise
     */
    private boolean approvePR(@Nonnull PullRequest pullRequest) {
        try {
            String baseUrl = applicationPropertiesService.getBaseUrl().toString();
            String project = pullRequest.getToRef().getRepository().getProject().getKey();
            String slug = pullRequest.getToRef().getRepository().getSlug();
            long prId = pullRequest.getId();

            String approveUrl = String.format("%s/rest/api/1.0/projects/%s/repos/%s/pull-requests/%d/approve",
                    baseUrl, project, slug, prId);

            log.info("Approving PR #{} at {}", prId, approveUrl);

            URL url = new URL(approveUrl);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);

            int responseCode = conn.getResponseCode();
            conn.disconnect();

            if (responseCode >= 200 && responseCode < 300) {
                log.info("✅ PR #{} approved successfully (HTTP {})", prId, responseCode);
                return true;
            } else {
                log.warn("Failed to approve PR #{}: HTTP {}", prId, responseCode);
                return false;
            }

        } catch (Exception e) {
            log.error("Failed to approve PR #{}: {}", pullRequest.getId(), e.getMessage(), e);
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
               Objects.equals(issue1.getLine(), issue2.getLine()) &&
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
                    pullRequest.getId(), e.getMessage(), e);
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

            ao.executeInTransaction(() -> {
                AIReviewHistory history = ao.create(AIReviewHistory.class);

                // PR information
                history.setPullRequestId(pullRequest.getId());
                history.setProjectKey(pullRequest.getToRef().getRepository().getProject().getKey());
                history.setRepositorySlug(pullRequest.getToRef().getRepository().getSlug());

                // Review execution
                history.setReviewStartTime(System.currentTimeMillis());
                history.setReviewEndTime(System.currentTimeMillis());
                history.setReviewStatus(result.getStatus().name());
                history.setModelUsed(model);

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

                // Files reviewed
                history.setFilesReviewed(result.getFilesReviewed());
                history.setTotalFiles(result.getFilesReviewed() + result.getFilesSkipped());

                // Store metrics as JSON
                history.setMetricsJson(gson.toJson(result.getMetrics()));

                history.save();
                log.info("Saved review history for PR #{} (ID: {})", pullRequest.getId(), history.getID());
                return null;
            });
        } catch (Exception e) {
            log.error("Failed to save review history for PR #{}: {}",
                    pullRequest.getId(), e.getMessage(), e);
        }
    }

    /**
     * Helper class to store chunk processing results.
     */
    private static class ChunkResult {
        final int index;
        final boolean success;
        final List<ReviewIssue> issues;
        final String error;
        final long elapsedMs;

        ChunkResult(int index, boolean success, List<ReviewIssue> issues, String error, long elapsedMs) {
            this.index = index;
            this.success = success;
            this.issues = issues;
            this.error = error;
            this.elapsedMs = elapsedMs;
        }
    }
}
