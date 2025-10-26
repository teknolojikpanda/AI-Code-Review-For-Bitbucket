package com.example.bitbucket.aireviewer.service;

import com.atlassian.activeobjects.external.ActiveObjects;
import com.atlassian.bitbucket.comment.AddCommentRequest;
import com.atlassian.bitbucket.comment.AddLineCommentRequest;
import com.atlassian.bitbucket.comment.Comment;
import com.atlassian.bitbucket.comment.CommentService;
import com.atlassian.bitbucket.comment.CommentSeverity;
import com.atlassian.bitbucket.comment.CommentThreadDiffAnchor;
import com.atlassian.bitbucket.comment.CommentThreadDiffAnchorType;
import com.atlassian.bitbucket.comment.LineNumberRange;
import com.atlassian.bitbucket.compare.CompareRequest;
import com.atlassian.bitbucket.compare.CompareService;
import com.atlassian.bitbucket.content.Change;
import com.atlassian.bitbucket.content.ChangeType;
import com.atlassian.bitbucket.content.Diff;
import com.atlassian.bitbucket.content.DiffHunk;
import com.atlassian.bitbucket.content.DiffLine;
import com.atlassian.bitbucket.content.DiffFileType;
import com.atlassian.bitbucket.content.DiffSegment;
import com.atlassian.bitbucket.content.DiffSegmentType;
import com.atlassian.bitbucket.content.DiffWhitespace;
import com.atlassian.bitbucket.io.TypeAwareOutputSupplier;
import com.atlassian.bitbucket.pull.PullRequest;
import com.atlassian.bitbucket.pull.PullRequestDiffRequest;
import com.atlassian.bitbucket.pull.PullRequestService;
import com.atlassian.bitbucket.repository.Repository;
import com.atlassian.bitbucket.repository.Repository;
import com.atlassian.bitbucket.repository.RepositoryService;
import com.atlassian.bitbucket.server.ApplicationPropertiesService;
import com.atlassian.bitbucket.util.Page;
import com.atlassian.bitbucket.util.PageRequest;
import com.atlassian.bitbucket.util.PageRequestImpl;
import com.atlassian.plugin.spring.scanner.annotation.imports.ComponentImport;
import com.example.bitbucket.aireviewer.ao.AIReviewHistory;
import com.example.bitbucket.aireviewer.dto.ReviewIssue;
import com.example.bitbucket.aireviewer.dto.ReviewResult;
import com.example.bitbucket.aireviewer.util.*;
// Using simple string building for JSON to avoid external dependencies
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashMap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Named;
import java.io.*;
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
    // Using simple string building for JSON
    
    private final PullRequestService pullRequestService;
    private final RepositoryService repositoryService;
    private final CommentService commentService;
    private final CompareService compareService;
    private final AIReviewerConfigService configService;
    private final ActiveObjects ao;
    private final ApplicationPropertiesService applicationPropertiesService;

    @Inject
    public AIReviewServiceImpl(
            @ComponentImport PullRequestService pullRequestService,
            @ComponentImport RepositoryService repositoryService,
            @ComponentImport CommentService commentService,
            @ComponentImport CompareService compareService,
            @ComponentImport ActiveObjects ao,
            @ComponentImport ApplicationPropertiesService applicationPropertiesService,
            AIReviewerConfigService configService) {
        this.pullRequestService = Objects.requireNonNull(pullRequestService, "pullRequestService cannot be null");
        this.repositoryService = Objects.requireNonNull(repositoryService, "repositoryService cannot be null");
        this.commentService = Objects.requireNonNull(commentService, "commentService cannot be null");
        this.compareService = Objects.requireNonNull(compareService, "compareService cannot be null");
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
            logPullRequestInfo(pullRequest);
            
            String diffText = fetchAndValidateDiff(pullRequest, metrics);
            if (diffText == null) {
                return buildSkippedResult(pullRequestId, "No changes to review", metrics);
            }
            
            PRSizeValidation sizeCheck = validatePRSize(diffText, metrics);
            if (!sizeCheck.isValid()) {
                return buildFailedResult(pullRequestId, sizeCheck.getMessage(), metrics);
            }
            
            Map<String, FileChange> fileChanges = analyzeFileChanges(diffText, metrics);
            Set<String> filesToReview = filterFilesForReview(fileChanges, metrics);
            
            if (filesToReview.isEmpty()) {
                return buildSuccessResult(pullRequestId, "No reviewable files found (all filtered)", 
                    0, fileChanges.size(), metrics);
            }
            
            List<DiffChunk> chunks = chunkDiff(diffText, filesToReview, metrics);
            List<ReviewIssue> issues = processAndValidateIssues(chunks, pullRequest, diffText, metrics);
            
            ReviewComparison comparison = compareWithPreviousReview(pullRequest, issues, metrics);
            int commentsPosted = postCommentsIfNeeded(issues, fileChanges, pullRequest, 
                overallStart, comparison, metrics);
            
            boolean approved = handleAutoApproval(issues, pullRequest, metrics);
            ReviewResult result = buildFinalResult(pullRequestId, issues, filesToReview.size(), 
                fileChanges.size(), commentsPosted, approved, metrics);
            
            saveReviewHistory(pullRequest, issues, result);
            return result;
            
        } catch (Exception e) {
            return handleReviewException(pullRequestId, e, metrics);
        } finally {
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
    
    private String fetchAndValidateDiff(@Nonnull PullRequest pr, @Nonnull MetricsCollector metrics) {
        Instant fetchStart = metrics.recordStart("fetchDiff");
        String diffText = fetchDiff(pr);
        metrics.recordEnd("fetchDiff", fetchStart);
        
        if (diffText == null || diffText.trim().isEmpty()) {
            log.warn("No diff content found for PR #{}", pr.getId());
            return null;
        }
        return diffText;
    }
    
    private PRSizeValidation validatePRSize(@Nonnull String diffText, @Nonnull MetricsCollector metrics) {
        Instant validateStart = metrics.recordStart("validateSize");
        PRSizeValidation sizeCheck = validatePRSize(diffText);
        metrics.recordEnd("validateSize", validateStart);
        metrics.recordMetric("diffSizeMB", (long) sizeCheck.getSizeMB());
        metrics.recordMetric("diffLines", sizeCheck.getLines());
        
        if (!sizeCheck.isValid()) {
            log.error("PR too large: {}", sizeCheck.getMessage());
        } else {
            log.info("PR size: {} lines, {} MB", sizeCheck.getLines(), 
                String.format("%.2f", sizeCheck.getSizeMB()));
        }
        return sizeCheck;
    }
    
    private Map<String, FileChange> analyzeFileChanges(@Nonnull String diffText, @Nonnull MetricsCollector metrics) {
        Instant analyzeStart = metrics.recordStart("analyzeDiff");
        Map<String, FileChange> fileChanges = analyzeDiffForSummary(diffText);
        metrics.recordEnd("analyzeDiff", analyzeStart);
        metrics.recordMetric("totalFiles", fileChanges.size());
        return fileChanges;
    }
    
    private Set<String> filterFilesForReview(@Nonnull Map<String, FileChange> fileChanges, @Nonnull MetricsCollector metrics) {
        Instant filterStart = metrics.recordStart("filterFiles");
        Set<String> filesToReview = filterFilesForReview(fileChanges.keySet());
        metrics.recordEnd("filterFiles", filterStart);
        metrics.recordMetric("filesToReview", filesToReview.size());
        metrics.recordMetric("filesSkipped", fileChanges.size() - filesToReview.size());
        
        log.info("Will review {} file(s), skipped {} file(s)",
                filesToReview.size(), fileChanges.size() - filesToReview.size());
        return filesToReview;
    }
    
    private List<DiffChunk> chunkDiff(@Nonnull String diffText, @Nonnull Set<String> filesToReview, @Nonnull MetricsCollector metrics) {
        Instant chunkStart = metrics.recordStart("chunkDiff");
        List<DiffChunk> chunks = smartChunkDiff(diffText, filesToReview);
        metrics.recordEnd("chunkDiff", chunkStart);
        metrics.recordMetric("chunks", chunks.size());
        
        log.info("Split into {} chunk(s) for processing", chunks.size());
        return chunks;
    }
    
    private List<ReviewIssue> processAndValidateIssues(@Nonnull List<DiffChunk> chunks, @Nonnull PullRequest pr, 
            @Nonnull String diffText, @Nonnull MetricsCollector metrics) {
        Instant ollamaStart = metrics.recordStart("ollamaProcessing");
        List<ReviewIssue> rawIssues = processChunksInParallel(chunks, pr);
        metrics.recordEnd("ollamaProcessing", ollamaStart);
        
        List<ReviewIssue> issues = new ArrayList<>();
        int invalidIssues = 0;
        
        for (ReviewIssue issue : rawIssues) {
            if (isValidIssue(issue, diffText)) {
                issues.add(issue);
            } else {
                invalidIssues++;
                log.warn("Filtered out invalid issue: {} at {}:{} - {}", 
                    issue.getType(), issue.getPath(), 
                    issue.getLineStart() != null ? issue.getLineStart() : issue.getLine(),
                    issue.getSummary());
            }
        }
        
        if (invalidIssues > 0) {
            log.warn("Filtered out {} invalid issues (null paths, invalid line numbers, mismatched code)", invalidIssues);
        }
        
        recordIssueMetrics(issues, invalidIssues, metrics);
        return issues;
    }
    
    private void recordIssueMetrics(@Nonnull List<ReviewIssue> issues, int invalidIssues, @Nonnull MetricsCollector metrics) {
        metrics.recordMetric("totalIssues", issues.size());
        metrics.recordMetric("invalidIssues", invalidIssues);
        
        long criticalCount = issues.stream().filter(i -> i.getSeverity() == ReviewIssue.Severity.CRITICAL).count();
        long highCount = issues.stream().filter(i -> i.getSeverity() == ReviewIssue.Severity.HIGH).count();
        long mediumCount = issues.stream().filter(i -> i.getSeverity() == ReviewIssue.Severity.MEDIUM).count();
        long lowCount = issues.stream().filter(i -> i.getSeverity() == ReviewIssue.Severity.LOW).count();
        
        metrics.recordMetric("criticalIssues", criticalCount);
        metrics.recordMetric("highIssues", highCount);
        metrics.recordMetric("mediumIssues", mediumCount);
        metrics.recordMetric("lowIssues", lowCount);
        
        log.info("Analysis complete: {} valid issues found ({} invalid filtered)", issues.size(), invalidIssues);
        log.info("Issue breakdown - Critical: {}, High: {}, Medium: {}, Low: {}",
                criticalCount, highCount, mediumCount, lowCount);
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
            metrics.recordMetric("resolvedIssues", resolvedIssues.size());
            metrics.recordMetric("newIssues", newIssues.size());
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
                String summaryText = buildSummaryComment(issues, fileChanges, pr, elapsedSeconds, 0, 
                    comparison.resolvedIssues, comparison.newIssues);
                Comment summaryComment = addPRComment(pr, summaryText);
                log.info("Posted summary comment with ID: {}", summaryComment.getId());
                
                commentsPosted = postIssueComments(issues, summaryComment, pr);
                log.info("✓ Posted {} issue comment replies", commentsPosted);
            } catch (Exception e) {
                log.error("❌ Failed to post comments: {} - {}", e.getClass().getSimpleName(), e.getMessage(), e);
            }
        } else {
            log.info("No issues found - skipping comment posting");
        }
        
        metrics.recordEnd("postComments", commentStart);
        metrics.recordMetric("commentsPosted", commentsPosted);
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
                metrics.recordMetric("autoApproved", 1);
            } else {
                log.warn("Failed to auto-approve PR #{}", pr.getId());
                metrics.recordMetric("autoApproveFailed", 1);
            }
        } else {
            log.info("PR #{} not auto-approved - critical/high issues present or auto-approve disabled", pr.getId());
            metrics.recordMetric("autoApproved", 0);
        }
        
        return approved;
    }
    
    private ReviewResult buildFinalResult(long pullRequestId, @Nonnull List<ReviewIssue> issues, int filesReviewed, 
            int totalFiles, int commentsPosted, boolean approved, @Nonnull MetricsCollector metrics) {
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
                .metrics(metrics.getMetrics())
                .build();
    }
    
    private ReviewResult buildSkippedResult(long pullRequestId, @Nonnull String message, @Nonnull MetricsCollector metrics) {
        return ReviewResult.builder()
                .pullRequestId(pullRequestId)
                .status(ReviewResult.Status.SKIPPED)
                .message(message)
                .filesReviewed(0)
                .filesSkipped(0)
                .metrics(metrics.getMetrics())
                .build();
    }
    
    private ReviewResult buildFailedResult(long pullRequestId, @Nonnull String message, @Nonnull MetricsCollector metrics) {
        return ReviewResult.builder()
                .pullRequestId(pullRequestId)
                .status(ReviewResult.Status.FAILED)
                .message(message)
                .filesReviewed(0)
                .filesSkipped(0)
                .metrics(metrics.getMetrics())
                .build();
    }
    
    private ReviewResult buildSuccessResult(long pullRequestId, @Nonnull String message, int filesReviewed, 
            int filesSkipped, @Nonnull MetricsCollector metrics) {
        return ReviewResult.builder()
                .pullRequestId(pullRequestId)
                .status(ReviewResult.Status.SUCCESS)
                .message(message)
                .filesReviewed(filesReviewed)
                .filesSkipped(filesSkipped)
                .metrics(metrics.getMetrics())
                .build();
    }
    
    private ReviewResult handleReviewException(long pullRequestId, @Nonnull Exception e, @Nonnull MetricsCollector metrics) {
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
     * Fetches the actual diff for a pull request matching REST API behavior.
     *
     * @param pullRequest the pull request
     * @return the diff content as String
     */
    @Nonnull
    private String fetchDiff(@Nonnull PullRequest pullRequest) {
        Repository targetRepo = pullRequest.getToRef().getRepository();
        String projectKey     = targetRepo.getProject().getKey();
        String repoSlug       = targetRepo.getSlug();
        long prId             = pullRequest.getId();
        Repository repo       = repositoryService.getBySlug(projectKey, repoSlug);

        try {
            log.info("Fetching diff for PR #{}", prId);
            
            if(repo == null) throw new IllegalArgumentException("Repository not found: " + projectKey + "/" + repoSlug);

            PullRequestDiffRequest req = new PullRequestDiffRequest.Builder(repo.getId(), prId, null)
                    .withComments(false)
                    .whitespace(DiffWhitespace.IGNORE_ALL)
                    .contextLines(PullRequestDiffRequest.DEFAULT_CONTEXT_LINES)
                    .build();

            ByteArrayOutputStream buffer = new ByteArrayOutputStream();

            TypeAwareOutputSupplier out = (String contentType) -> buffer;

            pullRequestService.streamDiff(req, out);

            return buffer.toString(StandardCharsets.UTF_8);
            
        } catch (Exception e) {
            log.error("Failed to fetch diff for PR #{}: {}", prId, e.getMessage());
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

        String[] lines = diffText.split("\n");
        StringBuilder currentFileDiff = new StringBuilder();
        String currentFile = null;

        for (String line : lines) {
            if (line.startsWith("diff --git ")) {
                if (currentFile != null && filesToReview.contains(currentFile)) {
                    String fileDiff = currentFileDiff.toString();
                    if ((currentContent.length() + fileDiff.length() > maxCharsPerChunk) || (currentFiles.size() >= maxFilesPerChunk)) {
                        if (currentContent.length() > 0) {
                            chunks.add(currentChunk.content(currentContent.toString()).files(currentFiles).build());
                            currentChunk = DiffChunk.builder().index(chunks.size());
                            currentContent = new StringBuilder();
                            currentFiles = new ArrayList<>();
                        }
                    }
                    currentContent.append(fileDiff);
                    currentFiles.add(currentFile);
                }
                String[] parts = line.split(" ");
                if (parts.length >= 3) {
                    currentFile = parts[2].substring(2);
                }
                currentFileDiff = new StringBuilder();
                currentFileDiff.append(line).append("\n");
            } else {
                currentFileDiff.append(line).append("\n");
            }
        }

        if (currentFile != null && filesToReview.contains(currentFile)) {
            String fileDiff = currentFileDiff.toString();
            if (currentContent.length() + fileDiff.length() > maxCharsPerChunk || currentFiles.size() >= maxFilesPerChunk) {
                if (currentContent.length() > 0) {
                    chunks.add(currentChunk.content(currentContent.toString()).files(currentFiles).build());
                    currentChunk = DiffChunk.builder().index(chunks.size());
                    currentContent = new StringBuilder();
                    currentFiles = new ArrayList<>();
                }
            }
            currentContent.append(fileDiff);
            currentFiles.add(currentFile);
        }

        if (currentContent.length() > 0) {
            chunks.add(currentChunk.content(currentContent.toString()).files(currentFiles).build());
        }

        if (chunks.size() > maxChunks) {
            chunks = chunks.subList(0, maxChunks);
        }

        // Add line numbers to each chunk to help AI identify exact line positions
        List<DiffChunk> annotatedChunks = new ArrayList<>();
        for (DiffChunk chunk : chunks) {
            String annotatedContent = addLineNumbersToDiff(chunk.getContent());
            annotatedChunks.add(DiffChunk.builder()
                    .index(chunk.getIndex())
                    .content(annotatedContent)
                    .files(chunk.getFiles())
                    .build());
        }

        return annotatedChunks;
    }

    /**
     * Adds line numbers to diff content to help AI identify exact line positions.
     * Annotates each line in the diff with [Line N] prefix for added lines.
     *
     * @param diffContent the original diff content
     * @return annotated diff with line numbers
     */
    @Nonnull
    private String addLineNumbersToDiff(@Nonnull String diffContent) {
        StringBuilder annotated = new StringBuilder();
        String[] lines = diffContent.split("\n");
        int currentDestLine = 0;
        boolean inHunk = false;

        for (String line : lines) {
            // Parse hunk header to get starting line number
            if (line.startsWith("@@")) {
                String[] parts = line.split("\\s+");
                for (String part : parts) {
                    if (part.startsWith("+")) {
                        String numPart = part.substring(1);
                        if (numPart.contains(",")) {
                            currentDestLine = Integer.parseInt(numPart.split(",")[0]);
                        } else {
                            currentDestLine = Integer.parseInt(numPart);
                        }
                        break;
                    }
                }
                annotated.append(line).append("\n");
                inHunk = true;
                continue;
            }

            // Skip file headers and other non-content lines
            if (line.startsWith("diff --git") || line.startsWith("index ") ||
                line.startsWith("---") || line.startsWith("+++") || line.startsWith("\\")) {
                annotated.append(line).append("\n");
                inHunk = false;
                continue;
            }

            if (!inHunk) {
                annotated.append(line).append("\n");
                continue;
            }

            // Annotate added lines with line numbers
            if (line.startsWith("+")) {
                annotated.append(String.format("[Line %d] %s\n", currentDestLine, line));
                currentDestLine++;
            } else if (line.startsWith("-")) {
                // Removed lines don't increment destination line counter
                annotated.append(line).append("\n");
            } else {
                // Context lines
                annotated.append(String.format("[Line %d] %s\n", currentDestLine, line));
                currentDestLine++;
            }
        }

        return annotated.toString();
    }

    /**
     * Builds the prompt for Ollama code review.
     *
     * @param diffChunk the diff chunk to review
     * @param pullRequest the pull request being reviewed
     * @param chunkIndex current chunk index (1-based)
     * @param totalChunks total number of chunks
     * @param config cached configuration
     * @return JSON request body as string
     */
    @Nonnull
    private String buildPrompt(@Nonnull DiffChunk diffChunk, @Nonnull PullRequest pullRequest,
                                int chunkIndex, int totalChunks, @Nonnull Map<String, Object> config) {
        String project = pullRequest.getToRef().getRepository().getProject().getKey();
        String slug = pullRequest.getToRef().getRepository().getSlug();
        long prId = pullRequest.getId();

        String systemPrompt = "MANDATORY COMPLIANCE RULES:\n" +
                "1. NEVER guess line numbers - ONLY use numbers from [Line N] tags\n" +
                "2. NEVER report issues without [Line N] annotation\n" +
                "3. NEVER use calculated or estimated line numbers\n" +
                "4. ONLY analyze '+' prefixed lines that have [Line N] tags\n" +
                "5. If you see line without [Line N], SKIP IT COMPLETELY";

        String userPrompt = String.format("ANALYZE DIFF - STRICT COMPLIANCE REQUIRED:\n\n" +
                "===DIFF===\n%s\n===END===\n\n" +
                "STEP 1: Find lines that look like '[Line 42] + problematic code'\n" +
                "STEP 2: Extract the number from [Line N] - this is your lineStart\n" +
                "STEP 3: Copy the exact '+' line as problematicCode\n" +
                "STEP 4: Get file path from 'diff --git' header above\n\n" +
                "EXAMPLE COMPLIANCE:\n" +
                "✓ CORRECT: '[Line 67] + String sql = \"SELECT * FROM users WHERE id=\" + id;'\n" +
                "  Report: lineStart: 67, path: 'src/User.java'\n\n" +
                "✗ WRONG: Line without [Line N] annotation\n" +
                "✗ WRONG: Guessing line number 67 when you see plain '+ code'\n" +
                "✗ WRONG: Using any number not from [Line N] tag\n\n" +
                "ZERO TOLERANCE: If no [Line N] tag, report NOTHING.",
                sanitizeForPrompt(diffChunk.getContent()));

        // Build JSON request using simple string building
        String model = (String) config.get("ollamaModel");

        // Escape JSON strings
        String escapedSystemPrompt = escapeJsonString(systemPrompt);
        String escapedUserPrompt = escapeJsonString(userPrompt);
        
        // Build JSON manually to avoid external dependencies
        StringBuilder json = new StringBuilder();
        json.append("{");
        json.append("\"model\":\"").append(model).append("\",");
        json.append("\"stream\":false,");
        json.append("\"format\":{");
        json.append("\"type\":\"object\",");
        json.append("\"properties\":{");
        json.append("\"issues\":{");
        json.append("\"type\":\"array\",");
        json.append("\"items\":{");
        json.append("\"type\":\"object\",");
        json.append("\"properties\":{");
        json.append("\"path\":{\"type\":\"string\"},");
        json.append("\"lineStart\":{\"type\":\"integer\"},");
        json.append("\"lineEnd\":{\"type\":\"integer\",\"description\":\"Optional ending line number for multiline issues\"},");
        json.append("\"severity\":{\"type\":\"string\",\"enum\":[\"low\",\"medium\",\"high\",\"critical\"]},");
        json.append("\"type\":{\"type\":\"string\"},");
        json.append("\"summary\":{\"type\":\"string\"},");
        json.append("\"details\":{\"type\":\"string\"},");

        json.append("\"problematicCode\":{\"type\":\"string\"}");
        json.append("},");
        json.append("\"required\":[\"path\",\"lineStart\",\"summary\",\"problematicCode\"]");
        json.append("}");
        json.append("}");
        json.append("},");
        json.append("\"required\":[\"issues\"]");
        json.append("},");
        json.append("\"messages\":[");
        json.append("{\"role\":\"system\",\"content\":\"").append(escapedSystemPrompt).append("\"},");
        json.append("{\"role\":\"user\",\"content\":\"").append(escapedUserPrompt).append("\"}]");
        json.append("}");
        
        return json.toString();
    }

    /**
     * Helper method to escape JSON strings.
     */
    private String escapeJsonString(String str) {
        if (str == null) return "";
        return str.replace("\\", "\\\\")
                  .replace("\"", "\\\"")
                  .replace("\n", "\\n")
                  .replace("\r", "\\r")
                  .replace("\t", "\\t");
    }

    /**
     * Calls Ollama API to analyze a code chunk.
     *
     * @param diffChunk the diff chunk to analyze
     * @param pullRequest the pull request
     * @param chunkIndex current chunk index (1-based)
     * @param totalChunks total chunks
     * @param model the model to use
     * @param config cached configuration to avoid service proxy issues
     * @return list of ReviewIssue objects
     */
    @Nonnull
    private List<ReviewIssue> callOllama(@Nonnull DiffChunk diffChunk, @Nonnull PullRequest pullRequest,
                                          int chunkIndex, int totalChunks, @Nonnull String model,
                                          @Nonnull Map<String, Object> config) {
        String ollamaUrl = (String) config.get("ollamaUrl");
        int ollamaTimeout = (int) config.get("ollamaTimeout");

        log.info("Calling Ollama for chunk {}/{} with model {} (timeout: {}ms)", chunkIndex, totalChunks, model, ollamaTimeout);

        try {
            // Build prompt
            String requestBody = buildPrompt(diffChunk, pullRequest, chunkIndex, totalChunks, config);

            // Call Ollama API
            URL url = new URL(ollamaUrl + "/api/chat");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            try {
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setConnectTimeout(15000); // 15 second connect timeout
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
                log.debug("Ollama response sample (first 1000 chars): {}", 
                         responseBody.length() > 1000 ? responseBody.substring(0, 1000) + "..." : responseBody);

                // Parse response
                return parseOllamaResponse(responseBody, diffChunk);

            } finally {
                conn.disconnect();
            }

        } catch (java.net.SocketTimeoutException e) {
            log.warn("Ollama timeout for chunk {}/{} after {}ms: {}", chunkIndex, totalChunks, ollamaTimeout, e.getMessage());
            throw new RuntimeException("Ollama timeout after " + ollamaTimeout + "ms: " + e.getMessage(), e);
        } catch (Exception e) {
            log.error("Failed to call Ollama for chunk {}/{}: {}", chunkIndex, totalChunks, sanitizeLogMessage(e.getMessage()));
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
            log.debug("Raw Ollama response (first 500 chars): {}", 
                     responseBody.length() > 500 ? responseBody.substring(0, 500) + "..." : responseBody);
            
            // Parse JSON response to Map
            ObjectMapper mapper = new ObjectMapper();
            Map<String, Object> responseMap = mapper.readValue(responseBody, Map.class);
            
            // Extract content from message.content or direct content
            String contentString = "";
            
            if (responseMap.containsKey("message")) {
                Map<String, Object> messageMap = (Map<String, Object>) responseMap.get("message");
                if (messageMap != null && messageMap.containsKey("content")) {
                    contentString = (String) messageMap.get("content");
                }
            }
            
            // If no nested content, try direct content
            if (contentString == null && responseMap.containsKey("content")) {
                contentString = (String) responseMap.get("content");
            }
            
            // If still no content, use response body directly
            if (contentString == null || contentString.trim().isEmpty()) {
                throw new NullPointerException("No nested content found, trying to parse response body directly");
            }

            Map<String, Object> content = mapper.readValue(contentString, Map.class);

            log.debug("Extracted content for parsing: " + content);
            
            // Parse the issues from content using Map approach
            List<ReviewIssue> issues = parseIssuesFromContentMap(content, diffChunk);
            log.info("Parsed {} issues from Ollama response", issues.size());
            
            return issues;

        } catch (Exception e) {
            log.error("Failed to parse Ollama response: {}", e.getMessage(), e);
            return Collections.emptyList();
        }
    }
    
    /**
     * Parses issues from the content string using Map-based approach.
     */
    @Nonnull
    private List<ReviewIssue> parseIssuesFromContentMap(@Nonnull Map<String, Object> content, @Nonnull DiffChunk diffChunk) {
        try {           
            // Extract issues array
            if (!content.containsKey("issues")) {
                log.warn("No 'issues' key found in content map");
                return Collections.emptyList();
            }
            
            Object issuesObj = content.get("issues");
            if (!(issuesObj instanceof List)) {
                log.warn("Issues is not a list: {}", issuesObj != null ? issuesObj.getClass() : "null");
                return Collections.emptyList();
            }
            
            @SuppressWarnings("unchecked")
            List<Object> issuesList = (List<Object>) issuesObj;
            
            List<ReviewIssue> issues = new ArrayList<>();
            for (Object issueObj : issuesList) {
                if (issueObj instanceof Map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> issueMap = (Map<String, Object>) issueObj;
                    ReviewIssue issue = parseIssueFromMap(issueMap);
                    if (issue != null) {
                        issues.add(issue);
                    }
                }
            }
            
            return issues;
            
        } catch (Exception e) {
            log.error("Failed to parse issues from content map: {}", e.getMessage(), e);
            // Fallback to original string parsing
            return new ArrayList<>();
        }
    }
    
    /**
     * Parses a single issue from a Map.
     */
    @Nullable
    private ReviewIssue parseIssueFromMap(@Nonnull Map<String, Object> issueMap) {
        try {
            String path = validateAndNormalizePath(issueMap);
            if (path == null) return null;
            
            Integer lineStart = parseLineNumber(issueMap.get("lineStart"), "lineStart");
            if (lineStart == null) return null;
            
            String summary = (String) issueMap.get("summary");
            if (summary == null) {
                log.debug("Missing required field: summary");
                return null;
            }
            
            Integer lineEnd = parseLineNumber(issueMap.get("lineEnd"), "lineEnd");
            String type = (String) issueMap.get("type");
            String details = (String) issueMap.get("details");
            String problematicCode = (String) issueMap.get("problematicCode");
            String severity = (String) issueMap.get("severity");
            
            ReviewIssue.Builder builder = ReviewIssue.builder()
                    .path(path)
                    .lineStart(lineStart)
                    .severity(mapSeverity(severity))
                    .type(type != null ? type : "Code Issue")
                    .summary(summary);
            
            if (lineEnd != null) builder.lineEnd(lineEnd);
            if (details != null) builder.details(details);
            if (problematicCode != null) builder.problematicCode(problematicCode);
            
            ReviewIssue issue = builder.build();
            log.debug("Parsed issue from map: {} at {}:{}", summary, path, lineStart);
            return issue;
            
        } catch (Exception e) {
            log.error("Error parsing issue from map: {}", e.getMessage());
            return null;
        }
    }
    
    /**
     * Validates and normalizes file path from issue map.
     */
    @Nullable
    private String validateAndNormalizePath(@Nonnull Map<String, Object> issueMap) {
        String path = (String) issueMap.get("path");
        if (path == null) {
            log.debug("Missing required field: path");
            return null;
        }
        
        // Be more lenient with path validation - only reject obviously invalid paths
        if (path.length() < 2 || path.equals("null") || path.equals("undefined")) {
            log.debug("Invalid path detected: {}", path);
            return null;
        }
        
        // Normalize path by removing protocol prefixes and leading slashes
        String normalized = path.replaceAll("^[a-zA-Z]+://", "").replaceAll("^/+", "");
        
        // Handle common path formats
        if (normalized.startsWith("a/") || normalized.startsWith("b/")) {
            normalized = normalized.substring(2);
        }
        
        return normalized;
    }
    
    /**
     * Parses line number from object, handling both Integer and String types.
     */
    @Nullable
    private Integer parseLineNumber(@Nullable Object lineObj, @Nonnull String fieldName) {
        if (lineObj == null) return null;
        
        if (lineObj instanceof Integer) {
            return (Integer) lineObj;
        }
        
        if (lineObj instanceof String) {
            String lineStr = (String) lineObj;
            if (lineStr.isEmpty()) return null;
            
            try {
                return Integer.parseInt(lineStr);
            } catch (NumberFormatException e) {
                log.debug("Invalid {}: {}", fieldName, lineObj);
                return null;
            }
        }
        
        log.debug("Invalid {} type: {}", fieldName, lineObj.getClass());
        return null;
    }
    
    /**
     * Unescapes a JSON string by removing escape characters.
     */
    @Nonnull
    private String unescapeJsonString(@Nonnull String str) {
        if (str == null) return "";
        return str.replace("\\\\", "\\")
                  .replace("\\\"", "\"")
                  .replace("\\n", "\n")
                  .replace("\\r", "\r")
                  .replace("\\t", "\t");
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
     * Robust Ollama call with retry logic and fallback model.
     *
     * @param diffChunk the diff chunk
     * @param pullRequest the pull request
     * @param chunkIndex chunk index
     * @param totalChunks total chunks
     * @param config cached configuration to avoid service proxy issues
     * @return list of issues
     */
    @Nonnull
    private List<ReviewIssue> robustOllamaCall(@Nonnull DiffChunk diffChunk, @Nonnull PullRequest pullRequest,
                                                int chunkIndex, int totalChunks, @Nonnull Map<String, Object> config) {
        String primaryModel = (String) config.get("ollamaModel");
        String fallbackModel = (String) config.get("fallbackModel");
        int maxRetries = (int) config.get("maxRetries");
        int baseRetryDelay = (int) config.get("baseRetryDelay");

        List<ReviewIssue> issues = null;
        Exception lastException = null;

        // Try primary model with retries
        for (int attempt = 0; attempt < maxRetries; attempt++) {
            try {
                log.info("Attempt {}/{} with primary model: {}", attempt + 1, maxRetries, primaryModel);
                issues = callOllama(diffChunk, pullRequest, chunkIndex, totalChunks, primaryModel, config);

                if (issues != null && !issues.isEmpty()) {
                    log.info("Primary model succeeded with {} issues", issues.size());
                    return issues;
                }

                log.warn("Primary model returned no issues");

            } catch (Exception e) {
                lastException = e;
                boolean isTimeout = e.getCause() instanceof java.net.SocketTimeoutException;
                log.warn("Attempt {}/{} failed with primary model{}: {}", 
                        attempt + 1, maxRetries, isTimeout ? " (timeout)" : "", e.getMessage());

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
                issues = callOllama(diffChunk, pullRequest, chunkIndex, totalChunks, fallbackModel, config);

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
        // Cache configuration at the start to avoid service proxy issues during long-running operations
        Map<String, Object> config;
        try {
            config = configService.getConfigurationAsMap();
        } catch (Exception e) {
            log.error("Failed to get configuration, using defaults: {}", e.getMessage());
            // Use default configuration if service is unavailable
            config = getDefaultConfiguration();
        }
        
        int parallelThreads = (int) config.get("parallelThreads");
        int threadCount = Math.min(parallelThreads, chunks.size());
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);

        log.info("Processing {} chunks in parallel with {} threads", chunks.size(), threadCount);

        List<Future<ChunkResult>> futures = new ArrayList<>();

        try {
            // Submit all chunks for processing
            for (int i = 0; i < chunks.size(); i++) {
                final int chunkIndex = i;
                final DiffChunk chunk = chunks.get(i);
                final Map<String, Object> cachedConfig = config; // Final reference for lambda

                Future<ChunkResult> future = executor.submit(() -> {
                    Instant startTime = Instant.now();
                    try {
                        log.info("Processing chunk {}/{}", chunkIndex + 1, chunks.size());
                        List<ReviewIssue> issues = robustOllamaCall(chunk, pullRequest, chunkIndex + 1, chunks.size(), cachedConfig);
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

            // Collect results with longer timeout for large models
            List<ReviewIssue> allIssues = new ArrayList<>();
            int successCount = 0;
            int failureCount = 0;
            int timeoutMinutes = 10; // Increased timeout for large models

            for (int i = 0; i < futures.size(); i++) {
                try {
                    ChunkResult result = futures.get(i).get(timeoutMinutes, TimeUnit.MINUTES);

                    if (result.success && result.issues != null) {
                        allIssues.addAll(result.issues);
                        successCount++;
                    } else {
                        failureCount++;
                        log.error("Chunk {} failed: {}", result.index + 1, result.error);
                    }

                } catch (TimeoutException e) {
                    failureCount++;
                    log.error("Chunk {} timed out after {} minutes", i + 1, timeoutMinutes);
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
                if (!executor.awaitTermination(15, TimeUnit.SECONDS)) {
                    executor.shutdownNow();
                }
            } catch (InterruptedException e) {
                executor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }
    
    /**
     * Returns default configuration when service is unavailable.
     */
    @Nonnull
    private Map<String, Object> getDefaultConfiguration() {
        Map<String, Object> defaults = new HashMap<>();
        defaults.put("ollamaUrl", "http://localhost:11434");
        defaults.put("ollamaModel", "qwen3-coder:30b");
        defaults.put("fallbackModel", "qwen3-coder:7b");
        defaults.put("ollamaTimeout", 300000); // 5 minutes
        defaults.put("maxRetries", 3);
        defaults.put("baseRetryDelay", 1000);
        defaults.put("parallelThreads", 4);
        return defaults;
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
                String loc = "";
                if (!"?".equals(issue.getLineRangeDisplay())) {
                    loc = "L" + issue.getLineRangeDisplay();
                    // Add multiline indicator
                    if (issue.getLineEnd() != null && !issue.getLineEnd().equals(issue.getLineStart())) {
                        loc += " (multiline)";
                    }
                }

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
                    String lineInfo = issue.getLineRangeDisplay();
                    if (issue.getLineEnd() != null && !issue.getLineEnd().equals(issue.getLineStart())) {
                        lineInfo += " (multiline)";
                    }
                    md.append("- ✓ `").append(issue.getPath()).append(":").append(lineInfo)
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
                    String lineInfo = issue.getLineRangeDisplay();
                    if (issue.getLineEnd() != null && !issue.getLineEnd().equals(issue.getLineStart())) {
                        lineInfo += " (multiline)";
                    }
                    md.append("- ").append(icon).append(" `").append(issue.getPath()).append(":").append(lineInfo)
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
            log.error("Failed to post PR comment: {}", e.getMessage());
            throw new RuntimeException("Failed to post PR comment: " + e.getMessage(), e);
        }
    }

    /**
     * Posts individual issue comments as line comments on the pull request.
     * Uses AddLineCommentRequest to anchor comments to specific lines in the diff.
     *
     * @param issues all issues to post
     * @param summaryComment the summary comment (not used for line comments)
     * @param pullRequest the pull request
     * @return number of comments successfully posted
     */
    private int postIssueComments(@Nonnull List<ReviewIssue> issues,
                                   @Nonnull Comment summaryComment,
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

                // Get line number - use lineStart if available, otherwise fall back to deprecated line field
                Integer lineNumber = issue.getLineStart() != null ? issue.getLineStart() : issue.getLine();

                if (lineNumber == null || lineNumber <= 0) {
                    log.warn("Skipping issue comment {}/{} - invalid line number: {} for file: {}",
                            i + 1, issuesToPost.size(), lineNumber, filePath);
                    commentsFailed++;
                    continue;
                }

                // Map issue severity to comment severity
                CommentSeverity commentSeverity = mapToCommentSeverity(issue.getSeverity());

                log.info("Creating line comment request for '{}:{}' with severity '{}'",
                        filePath, lineNumber, commentSeverity);

                // Create multiline-aware comment request for Bitbucket 9.6.5
                AddLineCommentRequest request = createMultilineCommentRequest(
                        pullRequest, 
                        commentText, 
                        filePath, 
                        issue, 
                        commentSeverity
                );

                log.info("Calling Bitbucket API to post line comment {}/{}", i + 1, issuesToPost.size());
                Comment comment = commentService.addComment(request);
                // Log with multiline information
                String commentType = (issue.getLineEnd() != null && !issue.getLineEnd().equals(issue.getLineStart())) 
                    ? "multiline comment" : "line comment";
                String lineInfo = (issue.getLineEnd() != null && !issue.getLineEnd().equals(issue.getLineStart()))
                    ? lineNumber + "-" + issue.getLineEnd() : String.valueOf(lineNumber);
                    
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
                Integer lineNumber = issue.getLineStart() != null ? issue.getLineStart() : issue.getLine();
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
            @Nonnull CommentSeverity commentSeverity) {
        
        Integer lineStart = issue.getLineStart() != null ? issue.getLineStart() : issue.getLine();
        
        log.debug("Creating comment request for {}:{}", filePath, lineStart);
        
        // Build the request using the correct constructor
        AddLineCommentRequest.Builder builder = new AddLineCommentRequest.Builder(
                pullRequest,
                commentText,
                CommentThreadDiffAnchorType.EFFECTIVE,
                filePath
        );
        
        // Set line number
        builder.line(lineStart);
        
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
     * Validates issue compliance with rules - now more lenient.
     */
    private boolean isValidIssue(@Nonnull ReviewIssue issue, @Nonnull String diffText) {
        String path = issue.getPath();
        Integer lineStart = issue.getLineStart() != null ? issue.getLineStart() : issue.getLine();
        
        if (path == null || path.trim().isEmpty()) {
            log.warn("Invalid file path: null or empty");
            return false;
        }
        
        if (lineStart == null || lineStart <= 0) {
            log.warn("Invalid line number: {} for {}", lineStart, path);
            return false;
        }
        
        // Rule 1: File path validation - more lenient
        if (!isExactFilePathInDiff(diffText, path)) {
            log.warn("Invalid file path: {}", path);
            return false;
        }
        
        // Rule 2: Line validation - check if line exists in diff (added or context)
        if (!isLineInDiff(diffText, path, lineStart)) {
            log.warn("Line {} not found in diff for {}", lineStart, path);
            return false;
        }
        
        return true;
    }
    
    /**
     * Validates exact file path exists in diff headers.
     */
    private boolean isExactFilePathInDiff(@Nonnull String diffText, @Nonnull String filePath) {
        // Normalize paths (remove Windows-style and src:/dst:// prefixes)
        String normalizedPath = filePath.replaceAll("^[a-zA-Z]+://", "").replaceAll("^/+", "");
        
        // Check multiple diff formats
        return diffText.contains("diff --git src://" + normalizedPath + " dst://" + normalizedPath);
    }
    
    /**
     * Validates line is in added code only (+ prefix).
     */
    private boolean isLineInAddedCode(@Nonnull String diffText, @Nonnull String filePath, int lineNumber) {
        String normalizedPath = filePath.replaceAll("^[a-zA-Z]+://", "").replaceAll("^/+", "");
        String[] lines = diffText.split("\n");
        boolean inFile = false;
        int currentLine = 0;
        boolean inHunk = false;
        
        for (String line : lines) {
            // Check for file header - be more flexible with path matching
            if (line.startsWith("diff --git") && 
                (line.contains(normalizedPath) || line.contains(filePath) || 
                 line.contains("a/" + normalizedPath) || line.contains("b/" + normalizedPath))) {
                inFile = true;
                inHunk = false;
                continue;
            }
            
            // Exit current file when we hit another diff header
            if (inFile && line.startsWith("diff --git") && 
                !(line.contains(normalizedPath) || line.contains(filePath) || 
                  line.contains("a/" + normalizedPath) || line.contains("b/" + normalizedPath))) {
                break;
            }
            
            if (!inFile) continue;
            
            // Parse hunk header to get starting line number
            if (line.startsWith("@@")) {
                try {
                    String[] parts = line.split("\\s+");
                    for (String part : parts) {
                        if (part.startsWith("+")) {
                            String numPart = part.substring(1);
                            currentLine = numPart.contains(",") ? 
                                Integer.parseInt(numPart.split(",")[0]) : 
                                Integer.parseInt(numPart);
                            break;
                        }
                    }
                    inHunk = true;
                } catch (NumberFormatException e) {
                    log.debug("Failed to parse hunk header: {}", line);
                }
            } else if (inHunk && line.startsWith("+") && !line.startsWith("+++")) {
                // This is an added line
                if (currentLine == lineNumber) return true;
                currentLine++;
            } else if (inHunk && !line.startsWith("-") && !line.startsWith("\\") && !line.startsWith("@@")) {
                // Context line (exists in both versions)
                currentLine++;
            }
        }
        return false;
    }
    
    /**
     * Validates exact line number from [Line N] annotations.
     * This is more lenient - if we can't find the annotation, we allow it through
     * since the line validation is the more important check.
     */
    private boolean isExactLineNumber(@Nonnull String diffText, @Nonnull String filePath, int lineNumber) {
        // Check for exact annotation first
        if (diffText.contains("[Line " + lineNumber + "]")) {
            return true;
        }
        
        // If no annotation found, be more lenient and just check if line exists in diff
        return isLineInDiff(diffText, filePath, lineNumber);
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
     * Checks if a line number exists in the diff for a specific file.
     * This validates that the line number actually appears in the changed code.
     *
     * @param diffContent the diff content
     * @param filePath the file path to check
     * @param lineNumber the line number to validate
     * @return true if the line exists in the diff
     */
    private boolean isLineInDiff(@Nonnull String diffContent, @Nonnull String filePath, int lineNumber) {
        String normalizedPath = filePath.replaceAll("^[a-zA-Z]+://", "").replaceAll("^/+", "");
        String[] lines = diffContent.split("\n");
        boolean inTargetFile = false;
        int currentDestLine = 0;
        boolean inHunk = false;

        for (String line : lines) {
            // Check if we're entering the target file's diff - be more flexible
            if (line.startsWith("diff --git ") && 
                (line.contains(normalizedPath) || line.contains(filePath) ||
                 line.contains("a/" + normalizedPath) || line.contains("b/" + normalizedPath))) {
                inTargetFile = true;
                inHunk = false;
                currentDestLine = 0;
                continue;
            }

            // Check if we're leaving the target file (entering another file)
            if (inTargetFile && line.startsWith("diff --git ") && 
                !(line.contains(normalizedPath) || line.contains(filePath) ||
                  line.contains("a/" + normalizedPath) || line.contains("b/" + normalizedPath))) {
                break;
            }

            if (!inTargetFile) {
                continue;
            }

            // Parse hunk header: @@ -oldStart,oldCount +newStart,newCount @@
            if (line.startsWith("@@")) {
                try {
                    String[] parts = line.split("\\s+");
                    for (String part : parts) {
                        if (part.startsWith("+")) {
                            // Extract the starting line number for new file
                            String numPart = part.substring(1); // Remove '+'
                            if (numPart.contains(",")) {
                                currentDestLine = Integer.parseInt(numPart.split(",")[0]);
                            } else {
                                currentDestLine = Integer.parseInt(numPart);
                            }
                            break;
                        }
                    }
                    inHunk = true;
                } catch (NumberFormatException e) {
                    log.debug("Failed to parse hunk header: {}", line);
                }
                continue;
            }

            if (!inHunk) continue;

            // Count lines in the new file (lines with '+' or context lines)
            if (line.startsWith("+") && !line.startsWith("+++")) {
                // This is an added line in the destination
                if (currentDestLine == lineNumber) {
                    return true;
                }
                currentDestLine++;
            } else if (!line.startsWith("-") && !line.startsWith("\\") && !line.startsWith("@@")) {
                // Context line (exists in both old and new)
                if (currentDestLine == lineNumber) {
                    return true;
                }
                currentDestLine++;
            }
        }

        return false;
    }

    /**
     * Finds the nearest valid line number in the diff for a file.
     * This is used when the AI reports a line number that doesn't exist in the diff.
     *
     * @param diffContent the diff content
     * @param filePath the file path
     * @param targetLine the target line number
     * @return the nearest valid line number, or null if none found
     */
    private Integer findNearestValidLine(@Nonnull String diffContent, @Nonnull String filePath, int targetLine) {
        String[] lines = diffContent.split("\n");
        boolean inTargetFile = false;
        int currentDestLine = 0;
        Integer firstValidLine = null;
        Integer lastValidLine = null;
        Integer nearestLine = null;
        int minDistance = Integer.MAX_VALUE;

        for (String line : lines) {
            // Check if we're entering the target file's diff
            if (line.startsWith("diff --git ") && line.contains(filePath)) {
                inTargetFile = true;
                currentDestLine = 0;
                continue;
            }

            // Check if we're leaving the target file
            if (inTargetFile && line.startsWith("diff --git ") && !line.contains(filePath)) {
                break;
            }

            if (!inTargetFile) {
                continue;
            }

            // Parse hunk header
            if (line.startsWith("@@")) {
                String[] parts = line.split("\\s+");
                for (String part : parts) {
                    if (part.startsWith("+")) {
                        String numPart = part.substring(1);
                        if (numPart.contains(",")) {
                            currentDestLine = Integer.parseInt(numPart.split(",")[0]);
                        } else {
                            currentDestLine = Integer.parseInt(numPart);
                        }
                        break;
                    }
                }
                continue;
            }

            // Track valid lines (added or context lines)
            if (line.startsWith("+") && !line.startsWith("+++")) {
                if (firstValidLine == null) {
                    firstValidLine = currentDestLine;
                }
                lastValidLine = currentDestLine;

                // Find nearest line to target
                int distance = Math.abs(currentDestLine - targetLine);
                if (distance < minDistance) {
                    minDistance = distance;
                    nearestLine = currentDestLine;
                }
                currentDestLine++;
            } else if (!line.startsWith("-") && !line.startsWith("\\")) {
                if (firstValidLine == null) {
                    firstValidLine = currentDestLine;
                }
                lastValidLine = currentDestLine;

                int distance = Math.abs(currentDestLine - targetLine);
                if (distance < minDistance) {
                    minDistance = distance;
                    nearestLine = currentDestLine;
                }
                currentDestLine++;
            }
        }

        // Return the nearest valid line, or first valid line if none found
        return nearestLine != null ? nearestLine : firstValidLine;
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
            long currentTime = System.currentTimeMillis();

            ao.executeInTransaction(() -> {
                // Create entity with all required fields in a single map
                Map<String, Object> params = new HashMap<>();
                
                // Required fields that must be set
                params.put("PULL_REQUEST_ID", pullRequest.getId());
                params.put("PROJECT_KEY", pullRequest.getToRef().getRepository().getProject().getKey());
                params.put("REPOSITORY_SLUG", pullRequest.getToRef().getRepository().getSlug());
                params.put("REVIEW_START_TIME", currentTime);
                params.put("REVIEW_STATUS", result.getStatus().name());
                
                AIReviewHistory history = ao.create(AIReviewHistory.class, params);

                // Set additional optional fields
                history.setReviewEndTime(currentTime);
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

                // Store metrics as simple string (JSON parsing removed)
                history.setMetricsJson(result.getMetrics().toString());

                history.save();
                log.info("Saved review history for PR #{} (ID: {})", pullRequest.getId(), history.getID());
                return null;
            });
        } catch (Exception e) {
            log.error("Failed to save review history for PR #{}: {}",
                    pullRequest.getId(), e);
        }
    }

    /**
     * Sanitizes log messages to prevent log injection attacks.
     */
    private String sanitizeLogMessage(String message) {
        if (message == null) return "null";
        return message.replaceAll("[\r\n\t]", "_");
    }

    /**
     * Sanitizes user input for prompt injection prevention.
     */
    private String sanitizeForPrompt(String input) {
        if (input == null) return "";
        return input.replaceAll("[<>&\"']", "_");
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
