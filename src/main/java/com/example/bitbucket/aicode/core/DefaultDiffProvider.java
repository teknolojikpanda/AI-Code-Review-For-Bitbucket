package com.example.bitbucket.aicode.core;

import com.atlassian.bitbucket.content.DiffWhitespace;
import com.atlassian.bitbucket.io.TypeAwareOutputSupplier;
import com.atlassian.bitbucket.pull.PullRequest;
import com.atlassian.bitbucket.pull.PullRequestDiffRequest;
import com.atlassian.bitbucket.pull.PullRequestService;
import com.atlassian.bitbucket.repository.Repository;
import com.atlassian.bitbucket.repository.RepositoryService;
import com.atlassian.plugin.spring.scanner.annotation.imports.ComponentImport;
import com.atlassian.plugin.spring.scanner.annotation.export.ExportAsService;
import com.example.bitbucket.aicode.api.DiffProvider;
import com.example.bitbucket.aicode.api.MetricsRecorder;
import com.example.bitbucket.aicode.model.ReviewConfig;
import com.example.bitbucket.aicode.model.ReviewContext;
import com.example.bitbucket.aicode.model.ReviewOverview;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import javax.inject.Inject;
import javax.inject.Named;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Streams pull request diff content and gathers per-file statistics.
 */
@Named
@ExportAsService(DiffProvider.class)
public class DefaultDiffProvider implements DiffProvider {

    private static final Logger log = LoggerFactory.getLogger(DefaultDiffProvider.class);

    private final PullRequestService pullRequestService;
    private final RepositoryService repositoryService;

    @Inject
    public DefaultDiffProvider(@ComponentImport PullRequestService pullRequestService,
                               @ComponentImport RepositoryService repositoryService) {
        this.pullRequestService = Objects.requireNonNull(pullRequestService, "pullRequestService");
        this.repositoryService = Objects.requireNonNull(repositoryService, "repositoryService");
    }

    @Nonnull
    @Override
    public ReviewContext collect(@Nonnull PullRequest pullRequest,
                                 @Nonnull ReviewConfig config,
                                 @Nonnull MetricsRecorder metrics) {
        Objects.requireNonNull(pullRequest, "pullRequest");
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(metrics, "metrics");

        Repository repo = repositoryService.getById(pullRequest.getToRef().getRepository().getId());
        if (repo == null) {
            throw new IllegalStateException("Repository not found for PR " + pullRequest.getId());
        }

        Instant start = metrics.recordStart("diff.stream");
        String diffText = streamDiff(repo, pullRequest.getId());
        metrics.recordEnd("diff.stream", start);

        if (diffText == null || diffText.isEmpty()) {
            log.info("No diff content for PR #{}", pullRequest.getId());
            metrics.recordMetric("diff.empty", true);
            return ReviewContext.builder()
                    .pullRequest(pullRequest)
                    .config(config)
                    .rawDiff("")
                    .fileStats(new HashMap<>())
                    .collectedAt(Instant.now())
                    .build();
        }

        Map<String, ReviewOverview.FileStats> initialStats = computeFileStats(diffText);

        Map<String, String> existingSections = splitDiffByFile(diffText);
        Map<String, String> fileDiffs = new LinkedHashMap<>();
        StringBuilder combined = new StringBuilder();

        for (String path : initialStats.keySet()) {
            String fallback = existingSections.getOrDefault(path, "");
            String streamed = streamSingleDiff(repo, pullRequest.getId(), path).orElse("");
            String chosen = !streamed.isEmpty() ? streamed : fallback;
            if (chosen.isEmpty()) {
                log.warn("No diff content available for path {}", path);
                continue;
            }
            fileDiffs.put(path, chosen);
            combined.append(chosen);
            if (!chosen.endsWith("\n")) {
                combined.append("\n");
            }
        }

        String effectiveDiff = combined.length() > 0 ? combined.toString() : diffText;

        byte[] finalBytes = effectiveDiff.getBytes(StandardCharsets.UTF_8);
        if (finalBytes.length > config.getMaxDiffBytes()) {
            throw new IllegalStateException(String.format(
                    "Diff size %,.2f MB exceeds limit of %,.2f MB",
                    finalBytes.length / (1024.0 * 1024.0),
                    config.getMaxDiffBytes() / (1024.0 * 1024.0)));
        }

        metrics.recordMetric("diff.bytes", finalBytes.length);
        metrics.recordMetric("diff.lines", effectiveDiff.split("\n", -1).length);

        Map<String, ReviewOverview.FileStats> finalStats = computeFileStats(effectiveDiff);
        metrics.recordMetric("diff.files", finalStats.size());

        return ReviewContext.builder()
                .pullRequest(pullRequest)
                .config(config)
                .rawDiff(effectiveDiff)
                .fileStats(finalStats)
                .fileDiffs(fileDiffs)
                .collectedAt(Instant.now())
                .build();
    }

    private String streamDiff(Repository repository, long pullRequestId) {
        try {
            PullRequestDiffRequest request = new PullRequestDiffRequest.Builder(repository.getId(), pullRequestId, null)
                    .withComments(false)
                    .whitespace(DiffWhitespace.IGNORE_ALL)
                    .contextLines(PullRequestDiffRequest.DEFAULT_CONTEXT_LINES)
                    .build();

            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            TypeAwareOutputSupplier supplier = (String contentType) -> buffer;
            pullRequestService.streamDiff(request, supplier);
            return buffer.toString(StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.error("Failed to stream diff for PR #{}: {}", pullRequestId, e.getMessage(), e);
            throw new IllegalStateException("Unable to fetch diff: " + e.getMessage(), e);
        }
    }

    private Optional<String> streamSingleDiff(Repository repository, long pullRequestId, String path) {
        try {
            PullRequestDiffRequest request = new PullRequestDiffRequest.Builder(repository.getId(), pullRequestId, path)
                    .withComments(false)
                    .whitespace(DiffWhitespace.IGNORE_ALL)
                    .contextLines(PullRequestDiffRequest.DEFAULT_CONTEXT_LINES)
                    .build();

            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            TypeAwareOutputSupplier supplier = (String contentType) -> buffer;
            pullRequestService.streamDiff(request, supplier);
            String diff = buffer.toString(StandardCharsets.UTF_8);
            if (diff.trim().isEmpty() || !diff.contains("diff --git")) {
                return Optional.empty();
            }
            return Optional.of(diff);
        } catch (Exception e) {
            log.warn("Failed to stream diff for file {} in PR #{}: {}", path, pullRequestId, e.getMessage());
            return Optional.empty();
        }
    }

    private Map<String, String> splitDiffByFile(String diffText) {
        Map<String, String> sections = new LinkedHashMap<>();
        if (diffText == null || diffText.isEmpty()) {
            return sections;
        }

        String[] lines = diffText.split("\n", -1);
        String currentFile = null;
        StringBuilder current = null;

        Pattern headerPattern = Pattern.compile("^diff --git\\s+(.+?)\\s+(.+)$");
        for (String line : lines) {
            Matcher matcher = headerPattern.matcher(line);
            if (matcher.matches()) {
                if (currentFile != null && current != null) {
                    sections.put(currentFile, current.toString());
                }
                String pathA = normalizeDiffPath(matcher.group(1));
                String pathB = normalizeDiffPath(matcher.group(2));
                currentFile = pathB != null ? pathB : pathA;
                current = new StringBuilder();
                current.append(line).append("\n");
            } else if (current != null) {
                current.append(line).append("\n");
            }
        }

        if (currentFile != null && current != null) {
            sections.put(currentFile, current.toString());
        }
        return sections;
    }

    private Map<String, ReviewOverview.FileStats> computeFileStats(String diffText) {
        Map<String, ReviewOverview.FileStats> stats = new HashMap<>();
        String currentFile = null;
        int additions = 0;
        int deletions = 0;

        String[] lines = diffText.split("\n", -1);
        Pattern headerPattern = Pattern.compile("^diff --git\\s+(.+?)\\s+(.+)$");

        for (String line : lines) {
            Matcher matcher = headerPattern.matcher(line);
            if (matcher.matches()) {
                if (currentFile != null) {
                    stats.put(currentFile, new ReviewOverview.FileStats(additions, deletions, false));
                }
                String pathA = normalizeDiffPath(matcher.group(1));
                String pathB = normalizeDiffPath(matcher.group(2));
                if (pathB == null || isDevNull(pathB)) {
                    currentFile = pathA;
                } else {
                    currentFile = pathB;
                }
                additions = 0;
                deletions = 0;
            } else if (line.startsWith("+") && !line.startsWith("+++")) {
                additions++;
            } else if (line.startsWith("-") && !line.startsWith("---")) {
                deletions++;
            }
        }

        if (currentFile != null) {
            stats.put(currentFile, new ReviewOverview.FileStats(additions, deletions, false));
        }
        return stats;
    }

    private String normalizeDiffPath(String rawPath) {
        if (rawPath == null) {
            return null;
        }
        String path = rawPath.trim();
        if (path.startsWith("\"") && path.endsWith("\"")) {
            path = path.substring(1, path.length() - 1);
        }
        if (path.startsWith("a/") || path.startsWith("b/")) {
            path = path.substring(2);
        }
        int schemeIdx = path.indexOf("://");
        if (schemeIdx >= 0) {
            path = path.substring(schemeIdx + 3);
        }
        if (path.startsWith("./")) {
            path = path.substring(2);
        }
        return path;
    }

    private boolean isDevNull(String path) {
        if (path == null) {
            return true;
        }
        String normalized = path.replace('\\', '/');
        return "/dev/null".equals(normalized) || "dev/null".equals(normalized);
    }
}
