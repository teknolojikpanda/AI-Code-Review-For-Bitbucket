package com.example.bitbucket.aicode.core;

import com.atlassian.plugin.spring.scanner.annotation.export.ExportAsService;
import com.example.bitbucket.aicode.api.ChunkPlanner;
import com.example.bitbucket.aicode.api.MetricsRecorder;
import com.example.bitbucket.aicode.model.LineRange;
import com.example.bitbucket.aicode.model.ReviewChunk;
import com.example.bitbucket.aicode.model.ReviewContext;
import com.example.bitbucket.aicode.model.ReviewOverview;
import com.example.bitbucket.aicode.model.ReviewPreparation;
import com.example.bitbucket.aicode.util.Diagnostics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import javax.annotation.Nonnull;
import javax.inject.Named;
import java.time.Instant;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Splits diffs into AI-sized chunks using diff hunk heuristics.
 */
@Named
@ExportAsService(ChunkPlanner.class)
public class HeuristicChunkPlanner implements ChunkPlanner {

    private static final Logger log = LoggerFactory.getLogger(HeuristicChunkPlanner.class);

    private static final Pattern DIFF_START = Pattern.compile("^diff --git\\s+(.+?)\\s+(.+)$");
    private static final Pattern HUNK_HEADER = Pattern.compile("^@@ .*\\+(\\d+)(?:,(\\d+))?.*@@");
    private static final Set<String> TEST_KEYWORDS = new HashSet<>(Arrays.asList("test", "spec", "fixture"));

    @Nonnull
    @Override
    public ReviewPreparation prepare(@Nonnull ReviewContext context, @Nonnull MetricsRecorder metrics) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(metrics, "metrics");

        String diff = context.getRawDiff();
        if (diff == null || diff.isEmpty()) {
            return ReviewPreparation.builder()
                    .context(context)
                    .overview(new ReviewOverview.Builder().build())
                    .chunks(Collections.emptyList())
                    .truncated(false)
                    .build();
        }

        Instant filterStart = metrics.recordStart("chunk.filter");
        Set<String> filesToReview = filterFiles(context);
        metrics.recordEnd("chunk.filter", filterStart);
        metrics.recordMetric("chunks.fileCandidates", filesToReview.size());
        if (filesToReview.isEmpty()) {
            log.warn("No reviewable files remain after filtering for PR #{} ({} candidates dropped)",
                    context.getPullRequest().getId(), context.getFileStats().size());
        } else if (log.isDebugEnabled()) {
            log.debug("Reviewable files for PR #{}: {}", context.getPullRequest().getId(), filesToReview);
        }

        ReviewOverview overview = buildOverview(context, filesToReview);
        metrics.recordMetric("chunks.overviewFiles", overview.getTotalFiles());

        Instant planStart = metrics.recordStart("chunk.plan");
        ChunkPlanResult result = planChunks(context, diff, filesToReview);
        metrics.recordEnd("chunk.plan", planStart);
        metrics.recordMetric("chunks.count", result.chunks.size());
        metrics.recordMetric("chunks.truncated", result.truncated);
        if (Diagnostics.isEnabled()) {
            Diagnostics.log(log, () -> String.format(
                    "Planning chunks for PR #%d with %d candidate file(s): %s",
                    context.getPullRequest().getId(),
                    filesToReview.size(),
                    filesToReview));
        }

        return ReviewPreparation.builder()
                .context(context)
                .overview(overview)
                .chunks(result.chunks)
                .truncated(result.truncated)
                .skippedFiles(result.skippedFiles)
                .build();
    }

    private Set<String> filterFiles(ReviewContext context) {
        Set<String> files = context.getFileStats().keySet();
        if (files.isEmpty()) {
            return Collections.emptySet();
        }

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
        if (Diagnostics.isEnabled()) {
            Diagnostics.log(log, () -> String.format(
                    "Filter setup for PR #%d -> allowedExtensions=%s, ignorePaths=%s, ignorePatterns=%d",
                    context.getPullRequest().getId(),
                    allowedExtensions,
                    ignorePaths,
                    ignorePatterns.size()));
        }

        return files.stream()
                .filter(file -> shouldReviewFile(file, allowedExtensions, ignorePatterns, ignorePaths, context))
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private boolean shouldReviewFile(String file,
                                     Set<String> allowedExtensions,
                                     List<Pattern> ignorePatterns,
                                     List<String> ignorePaths,
                                     ReviewContext context) {
        String lower = file.toLowerCase();
        for (String path : ignorePaths) {
            if (!path.isEmpty() && lower.contains(path)) {
                if (log.isDebugEnabled()) {
                    log.debug("Skipping {} because it matches ignore path '{}'", file, path);
                }
                return false;
            }
        }

        for (Pattern pattern : ignorePatterns) {
            if (pattern.matcher(lower).matches()) {
                if (log.isDebugEnabled()) {
                    log.debug("Skipping {} because it matches ignore pattern {}", file, pattern);
                }
                return false;
            }
        }

        int idx = file.lastIndexOf('.');
        if (idx < 0) {
            if (log.isDebugEnabled()) {
                log.debug("Skipping {} because it has no extension", file);
            }
            return false;
        }
        String ext = file.substring(idx + 1).toLowerCase();
        if (!allowedExtensions.isEmpty() && !allowedExtensions.contains(ext)) {
            if (log.isDebugEnabled()) {
                log.debug("Skipping {} because extension '{}' not in allow-list {}", file, ext, allowedExtensions);
            }
            return false;
        }

        if (context.getConfig().getProfile().isSkipGeneratedFiles() && looksGenerated(file)) {
            if (log.isDebugEnabled()) {
                log.debug("Skipping generated file {}", file);
            }
            return false;
        }
        if (!context.getConfig().getProfile().isReviewTests() && looksLikeTestFile(file)) {
            if (log.isDebugEnabled()) {
                log.debug("Skipping test file {}", file);
            }
            return false;
        }
        return true;
    }

    private boolean looksGenerated(String file) {
        String lower = file.toLowerCase();
        return lower.contains("generated") || lower.endsWith(".g.dart") || lower.contains("build/");
    }

    private boolean looksLikeTestFile(String file) {
        String lower = file.toLowerCase();
        return TEST_KEYWORDS.stream().anyMatch(lower::contains);
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

    private ReviewOverview buildOverview(ReviewContext context, Set<String> filesToReview) {
        ReviewOverview.Builder builder = new ReviewOverview.Builder();
        context.getFileStats().forEach((path, stats) -> {
            if (filesToReview.contains(path)) {
                builder.addFileStats(path, stats);
            }
        });
        return builder.build();
    }

    private ChunkPlanResult planChunks(ReviewContext context,
                                       String diff,
                                       Set<String> filesToReview) {
        int maxChars = context.getConfig().getMaxCharsPerChunk();
        int maxFiles = context.getConfig().getMaxFilesPerChunk();
        int maxChunks = context.getConfig().getMaxChunks();

        List<ReviewChunk> chunks = new ArrayList<>();
        boolean truncated = false;
        List<String> filesWithoutHunks = new ArrayList<>();

        Map<String, FileDiff> fileDiffs = extractFileDiffs(diff, filesToReview);
        if (log.isDebugEnabled()) {
            log.debug("Chunk planner evaluating files: {}", fileDiffs.keySet());
        }
        if (Diagnostics.isEnabled()) {
            Diagnostics.log(log, () -> String.format(
                    "Extracted %d file diff(s) for PR #%d: %s",
                    fileDiffs.size(),
                    context.getPullRequest().getId(),
                    fileDiffs.keySet()));
            fileDiffs.forEach((path, fd) ->
                    Diagnostics.log(log, () -> String.format(
                            "File %s has %d hunk(s) captured",
                            path,
                            fd.hunks != null ? fd.hunks.size() : 0)));
        }
        fileDiffs.entrySet().removeIf(entry -> {
            if (entry.getValue().hunks.isEmpty()) {
                filesWithoutHunks.add(entry.getKey());
                return true;
            }
            return false;
        });

        if (!filesWithoutHunks.isEmpty()) {
            log.warn("HeuristicChunkPlanner: skipping {} file(s) with no textual changes: {}",
                    filesWithoutHunks.size(), filesWithoutHunks);
        }

        int chunkIndex = 0;
        ReviewChunk.Builder builder = ReviewChunk.builder()
                .id("chunk-" + chunkIndex)
                .index(chunkIndex);
        StringBuilder buffer = new StringBuilder();
        Set<String> chunkFiles = new LinkedHashSet<>();
        Map<String, LineRange> chunkRanges = new LinkedHashMap<>();

        for (FileDiff fileDiff : fileDiffs.values()) {
            boolean fileAddedInChunk = chunkFiles.contains(fileDiff.path);
            for (Hunk hunk : fileDiff.hunks) {
                boolean needsHeader = !fileAddedInChunk && !chunkFiles.contains(fileDiff.path);
                String hunkContent = (needsHeader ? fileDiff.header : "") + hunk.content;

                boolean wouldExceed = buffer.length() + hunkContent.length() > maxChars
                        || (!chunkFiles.contains(fileDiff.path) && chunkFiles.size() >= maxFiles);

                if (wouldExceed && buffer.length() > 0) {
                    String chunkContent = buffer.toString();
                    finalizeChunk(context, chunks, builder, chunkContent, chunkFiles, chunkRanges);

                    if (chunks.size() >= maxChunks) {
                        truncated = true;
                        return new ChunkPlanResult(chunks, truncated, filesWithoutHunks);
                    }

                    chunkIndex++;
                    builder = ReviewChunk.builder()
                            .id("chunk-" + chunkIndex)
                            .index(chunkIndex);
                    buffer = new StringBuilder();
                    chunkFiles = new LinkedHashSet<>();
                    chunkRanges = new LinkedHashMap<>();
                    fileAddedInChunk = false;
                    needsHeader = true;
                }

                if (needsHeader) {
                    chunkFiles.add(fileDiff.path);
                } else if (!chunkFiles.contains(fileDiff.path)) {
                    chunkFiles.add(fileDiff.path);
                }

                buffer.append(hunkContent);
                chunkRanges.merge(
                        fileDiff.path,
                        hunk.range,
                        (prev, next) -> LineRange.of(
                                Math.min(prev.getStart(), next.getStart()),
                                Math.max(prev.getEnd(), next.getEnd())));
                fileAddedInChunk = true;
            }
        }

        if (buffer.length() > 0 && chunks.size() < maxChunks) {
            String chunkContent = buffer.toString();
            finalizeChunk(context, chunks, builder, chunkContent, chunkFiles, chunkRanges);
        }

        if (chunks.size() > maxChunks) {
            truncated = true;
            chunks = chunks.subList(0, maxChunks);
        }

        if (truncated) {
            log.warn("Chunk planning truncated for PR #{}: reduced to {} chunks (max={})",
                    context.getPullRequest().getId(), chunks.size(), maxChunks);
        }
        return new ChunkPlanResult(chunks, truncated, filesWithoutHunks);
    }

    private void finalizeChunk(ReviewContext context,
                               List<ReviewChunk> chunks,
                               ReviewChunk.Builder builder,
                               String content,
                               Set<String> chunkFiles,
                               Map<String, LineRange> chunkRanges) {
        builder.content(content);
        builder.files(new ArrayList<>(chunkFiles));
        builder.primaryRanges(new LinkedHashMap<>(chunkRanges));
        ReviewChunk chunk = builder.build();
        chunks.add(chunk);
        if (Diagnostics.isEnabled()) {
            Diagnostics.log(log, () -> String.format(
                    "Chunk %s created for PR #%d (files=%d, chars=%d): %s",
                    chunk.getId(),
                    context.getPullRequest().getId(),
                    chunk.getFiles().size(),
                    content.length(),
                    chunk.getFiles()));
            Diagnostics.dumpChunk(context.getPullRequest().getId(), chunk);
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

    private Map<String, FileDiff> extractFileDiffs(String diff, Set<String> filesToReview) {
        Map<String, FileDiff> fileDiffs = new LinkedHashMap<>();
        String currentFile = null;
        StringBuilder currentHeader = new StringBuilder();
        StringBuilder currentHunk = null;
        List<Hunk> hunks = null;
        LineRange currentRange = null;

        String[] lines = diff.split("\n", -1);
        for (String rawLine : lines) {
            String line = stripTrailingCarriageReturn(rawLine);
            Matcher diffHeader = DIFF_START.matcher(line);
            if (diffHeader.matches()) {
                if (currentFile != null && hunks != null && filesToReview.contains(currentFile)) {
                    if (currentHunk != null && currentRange != null) {
                        hunks.add(new Hunk(currentHunk.toString(), currentRange));
                    }
                    fileDiffs.put(currentFile, new FileDiff(currentFile, currentHeader.toString(), hunks));
                }
                String pathA = normalizeDiffPath(diffHeader.group(1));
                String pathB = normalizeDiffPath(diffHeader.group(2));
                if (pathB != null && filesToReview.contains(pathB)) {
                    currentFile = pathB;
                } else if (pathA != null && filesToReview.contains(pathA)) {
                    currentFile = pathA;
                } else if (pathB != null) {
                    currentFile = pathB;
                } else {
                    currentFile = pathA;
                }
                currentHeader = new StringBuilder();
                currentHeader.append(line).append("\n");
                hunks = new ArrayList<>();
                currentHunk = null;
                currentRange = null;
                continue;
            }

            if (currentFile == null) {
                continue;
            }

            if (line.startsWith("index ") ||
                    line.startsWith("--- ") ||
                    line.startsWith("+++ ") ||
                    line.startsWith("new file") ||
                    line.startsWith("deleted file mode")) {
                currentHeader.append(line).append("\n");
                continue;
            }

            Matcher hunkHeader = HUNK_HEADER.matcher(line);
            if (hunkHeader.matches()) {
                if (currentHunk != null && currentRange != null) {
                    hunks.add(new Hunk(currentHunk.toString(), currentRange));
                }
                currentHunk = new StringBuilder();
                currentHunk.append(line).append("\n");

                int start = Integer.parseInt(hunkHeader.group(1));
                int length = hunkHeader.group(2) != null ? Integer.parseInt(hunkHeader.group(2)) : 1;
                currentRange = LineRange.of(start, start + Math.max(length - 1, 0));
                continue;
            }

            if (currentHunk != null) {
                currentHunk.append(line).append("\n");
            }
        }

        if (currentFile != null && hunks != null && filesToReview.contains(currentFile)) {
            if (currentHunk != null && currentRange != null) {
                hunks.add(new Hunk(currentHunk.toString(), currentRange));
            }
            fileDiffs.put(currentFile, new FileDiff(currentFile, currentHeader.toString(), hunks));
        }
        return fileDiffs;
    }

    private String stripTrailingCarriageReturn(String line) {
        if (line != null && line.endsWith("\r")) {
            return line.substring(0, line.length() - 1);
        }
        return line;
    }

    private static final class FileDiff {
        final String path;
        final String header;
        final List<Hunk> hunks;

        private FileDiff(String path, String header, List<Hunk> hunks) {
            this.path = path;
            this.header = header;
            this.hunks = hunks;
        }
    }

    private static final class Hunk {
        final String content;
        final LineRange range;

        private Hunk(String content, LineRange range) {
            this.content = content;
            this.range = range;
        }
    }

    private static final class ChunkPlanResult {
        final List<ReviewChunk> chunks;
        final boolean truncated;
        final List<String> skippedFiles;

        private ChunkPlanResult(List<ReviewChunk> chunks, boolean truncated, List<String> skippedFiles) {
            this.chunks = chunks;
            this.truncated = truncated;
            this.skippedFiles = skippedFiles;
        }
    }
}
