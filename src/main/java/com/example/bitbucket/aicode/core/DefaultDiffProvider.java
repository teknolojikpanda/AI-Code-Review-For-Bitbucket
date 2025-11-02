package com.example.bitbucket.aicode.core;

import com.atlassian.bitbucket.content.DiffWhitespace;
import com.atlassian.bitbucket.io.TypeAwareOutputSupplier;
import com.atlassian.bitbucket.pull.PullRequest;
import com.atlassian.bitbucket.pull.PullRequestDiffRequest;
import com.atlassian.bitbucket.pull.PullRequestService;
import com.atlassian.bitbucket.repository.Repository;
import com.atlassian.plugin.spring.scanner.annotation.imports.ComponentImport;
import com.atlassian.plugin.spring.scanner.annotation.export.ExportAsService;
import com.example.bitbucket.aicode.api.DiffProvider;
import com.example.bitbucket.aicode.api.MetricsRecorder;
import com.example.bitbucket.aicode.model.ReviewConfig;
import com.example.bitbucket.aicode.model.ReviewContext;
import com.example.bitbucket.aicode.model.ReviewOverview;
import com.example.bitbucket.aicode.model.ReviewFileMetadata;
import com.example.bitbucket.aicode.util.Diagnostics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import javax.inject.Inject;
import javax.inject.Named;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Streams pull request diff content and gathers per-file statistics.
 */
@Named
@ExportAsService(DiffProvider.class)
public class DefaultDiffProvider implements DiffProvider {

    private static final Logger log = LoggerFactory.getLogger(DefaultDiffProvider.class);
    private static final Pattern DIFF_HEADER_PATTERN = Pattern.compile("^diff --git\\s+(.+?)\\s+(.+)$");

    private final PullRequestService pullRequestService;

    @Inject
    public DefaultDiffProvider(@ComponentImport PullRequestService pullRequestService) {
        this.pullRequestService = Objects.requireNonNull(pullRequestService, "pullRequestService");
    }

    @Nonnull
    @Override
    public ReviewContext collect(@Nonnull PullRequest pullRequest,
                                 @Nonnull ReviewConfig config,
                                 @Nonnull MetricsRecorder metrics) {
        Objects.requireNonNull(pullRequest, "pullRequest");
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(metrics, "metrics");

        Repository repo = pullRequest.getToRef() != null ? pullRequest.getToRef().getRepository() : null;
        if (repo == null) {
            throw new IllegalStateException("Repository not found for PR " + pullRequest.getId());
        }

        Instant start = metrics.recordStart("diff.stream");
        DiffBundle bundle = streamDiff(repo, pullRequest.getId(), config.getMaxDiffBytes());
        metrics.recordEnd("diff.stream", start);
        Diagnostics.dumpRawDiff(pullRequest.getId(), bundle.rawDiff);

        String rawDiff = bundle.rawDiff;
        if (rawDiff == null || rawDiff.isEmpty()) {
            log.info("No diff content for PR #{}", pullRequest.getId());
            metrics.recordMetric("diff.empty", true);
            return ReviewContext.builder()
                    .pullRequest(pullRequest)
                    .config(config)
                    .rawDiff("")
                    .fileStats(new HashMap<>())
                    .fileMetadata(Collections.emptyMap())
                    .collectedAt(Instant.now())
                    .build();
        }

        Map<String, ReviewOverview.FileStats> finalStats = bundle.fileStats;
        Map<String, String> fileDiffs = bundle.fileDiffs;
        if (Diagnostics.isEnabled()) {
            Diagnostics.log(log, () -> String.format(
                    "Diff summary for PR #%d -> files=%d, bytes=%d, additions=%d, deletions=%d",
                    pullRequest.getId(),
                    finalStats.size(),
                    bundle.bytes,
                    finalStats.values().stream().mapToInt(ReviewOverview.FileStats::getAdditions).sum(),
                    finalStats.values().stream().mapToInt(ReviewOverview.FileStats::getDeletions).sum()));
            Diagnostics.log(log, () -> String.format(
                    "Final file stats for PR #%d: %s",
                    pullRequest.getId(),
                    finalStats.keySet()));
        }

        if (Diagnostics.isEnabled()) {
            fileDiffs.forEach((path, diff) -> Diagnostics.dumpFileDiff(pullRequest.getId(), path, diff));
        }

        metrics.recordMetric("diff.bytes", bundle.bytes);
        metrics.recordMetric("diff.lines", bundle.lines);
        metrics.recordMetric("diff.files", finalStats.size());

        Map<String, ReviewFileMetadata> metadata = FileMetadataExtractor.extract(finalStats);

        return ReviewContext.builder()
                .pullRequest(pullRequest)
                .config(config)
                .rawDiff(rawDiff)
                .fileStats(finalStats)
                .fileDiffs(fileDiffs)
                .fileMetadata(metadata)
                .collectedAt(Instant.now())
                .build();
    }

    private DiffBundle streamDiff(Repository repository, long pullRequestId, int maxDiffBytes) {
        try {
            PullRequestDiffRequest request = new PullRequestDiffRequest.Builder(repository.getId(), pullRequestId, null)
                    .withComments(false)
                    .whitespace(DiffWhitespace.IGNORE_ALL)
                    .contextLines(PullRequestDiffRequest.DEFAULT_CONTEXT_LINES)
                    .build();

            StreamingDiffAccumulator accumulator = new StreamingDiffAccumulator(maxDiffBytes);
            TypeAwareOutputSupplier supplier = contentType -> accumulator;
            pullRequestService.streamDiff(request, supplier);
            accumulator.finish();
            return accumulator.toBundle();
        } catch (DiffTooLargeException e) {
            log.error("Diff for PR #{} exceeds configured limit ({} bytes > {} bytes)",
                    pullRequestId, e.actualBytes, e.maxBytes);
            throw e;
        } catch (Exception e) {
            log.error("Failed to stream diff for PR #{}: {}", pullRequestId, e.getMessage(), e);
            throw new IllegalStateException("Unable to fetch diff: " + e.getMessage(), e);
        }
    }

    private final class StreamingDiffAccumulator extends OutputStream {

        private final long maxBytes;
        private final Map<String, MutableFileState> files = new LinkedHashMap<>();
        private final StringBuilder buffer = new StringBuilder();
        private final StringBuilder rawDiff = new StringBuilder();
        private long totalBytes;
        private int totalLines;
        private String currentFileKey;
        private MutableFileState currentFile;

        StreamingDiffAccumulator(long maxBytes) {
            this.maxBytes = maxBytes;
        }

        @Override
        public void write(int b) throws IOException {
            byte[] single = {(byte) b};
            write(single, 0, 1);
        }

        @Override
        public void write(byte[] b, int off, int len) {
            if (len <= 0) {
                return;
            }
            totalBytes += len;
            if (maxBytes > 0 && totalBytes > maxBytes) {
                throw new DiffTooLargeException(totalBytes, maxBytes);
            }
            String chunk = new String(b, off, len, StandardCharsets.UTF_8);
            buffer.append(chunk);
            drain(false);
        }

        void finish() {
            drain(true);
        }

        private void drain(boolean flushRemainder) {
            int newlineIndex;
            while ((newlineIndex = buffer.indexOf("\n")) >= 0) {
                String line = buffer.substring(0, newlineIndex + 1);
                buffer.delete(0, newlineIndex + 1);
                handleLine(line, true);
            }
            if (flushRemainder && buffer.length() > 0) {
                String remainder = buffer.toString();
                buffer.setLength(0);
                handleLine(remainder, false);
            }
        }

        private void handleLine(String line, boolean endsWithNewline) {
            rawDiff.append(line);
            if (endsWithNewline || line.length() > 0) {
                totalLines++;
            }

            String effective = endsWithNewline && line.endsWith("\n")
                    ? line.substring(0, line.length() - 1)
                    : line;

            Matcher matcher = DIFF_HEADER_PATTERN.matcher(effective);
            if (matcher.matches()) {
                switchCurrentFile(line, matcher);
                return;
            }

            if (currentFile == null) {
                return;
            }

            currentFile.append(line);
            if (effective.startsWith("+") && !effective.startsWith("+++")) {
                currentFile.additions++;
            } else if (effective.startsWith("-") && !effective.startsWith("---")) {
                currentFile.deletions++;
            } else if (effective.startsWith("Binary files") || effective.startsWith("GIT binary patch")) {
                currentFile.binary = true;
            }
        }

        private void switchCurrentFile(String rawLine, Matcher matcher) {
            String pathA = normalizeDiffPath(matcher.group(1));
            String pathB = normalizeDiffPath(matcher.group(2));
            String key = (pathB == null || isDevNull(pathB)) ? pathA : pathB;
            if (key == null || key.isEmpty()) {
                key = pathA != null ? pathA : pathB;
            }
            if (key == null || key.isEmpty()) {
                log.debug("Skipping diff header with unresolved path: {}", matcher.group(0));
                currentFileKey = null;
                currentFile = null;
                return;
            }
            currentFileKey = key;
            currentFile = files.computeIfAbsent(currentFileKey, __ -> new MutableFileState());
            currentFile.append(rawLine);
        }

        DiffBundle toBundle() {
            Map<String, String> diffs = new LinkedHashMap<>();
            Map<String, ReviewOverview.FileStats> stats = new LinkedHashMap<>();
            for (Map.Entry<String, MutableFileState> entry : files.entrySet()) {
                MutableFileState state = entry.getValue();
                diffs.put(entry.getKey(), state.content.toString());
                stats.put(entry.getKey(), new ReviewOverview.FileStats(
                        state.additions,
                        state.deletions,
                        state.binary));
            }
            return new DiffBundle(rawDiff.toString(), diffs, stats, totalBytes, totalLines);
        }
    }

    private static final class MutableFileState {
        final StringBuilder content = new StringBuilder();
        int additions;
        int deletions;
        boolean binary;

        void append(String line) {
            content.append(line);
        }
    }

    private static final class DiffBundle {
        final String rawDiff;
        final Map<String, String> fileDiffs;
        final Map<String, ReviewOverview.FileStats> fileStats;
        final long bytes;
        final int lines;

        DiffBundle(String rawDiff,
                   Map<String, String> fileDiffs,
                   Map<String, ReviewOverview.FileStats> fileStats,
                   long bytes,
                   int lines) {
            this.rawDiff = rawDiff;
            this.fileDiffs = fileDiffs;
            this.fileStats = fileStats;
            this.bytes = bytes;
            this.lines = lines;
        }
    }

    private static final class DiffTooLargeException extends IllegalStateException {
        final long actualBytes;
        final long maxBytes;

        DiffTooLargeException(long actualBytes, long maxBytes) {
            super(String.format(Locale.ENGLISH,
                    "Diff size %,.2f MB exceeds limit of %,.2f MB",
                    actualBytes / (1024.0 * 1024.0),
                    maxBytes / (1024.0 * 1024.0)));
            this.actualBytes = actualBytes;
            this.maxBytes = maxBytes;
        }
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
