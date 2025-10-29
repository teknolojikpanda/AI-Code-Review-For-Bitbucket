package com.example.bitbucket.aicode.core;

import com.example.bitbucket.aicode.api.ChunkStrategy;
import com.example.bitbucket.aicode.api.MetricsRecorder;
import com.example.bitbucket.aicode.model.LineRange;
import com.example.bitbucket.aicode.model.ReviewChunk;
import com.example.bitbucket.aicode.model.ReviewContext;
import com.example.bitbucket.aicode.util.Diagnostics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import javax.inject.Named;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Default chunking strategy that preserves existing size-based behaviour.
 * Files are added to a chunk until size/file thresholds are reached.
 */
@Named
public class SizeFirstChunkStrategy implements ChunkStrategy {

    private static final Logger log = LoggerFactory.getLogger(SizeFirstChunkStrategy.class);

    private static final Pattern DIFF_START = Pattern.compile("^diff --git\\s+(.+?)\\s+(.+)$");
    private static final Pattern HUNK_HEADER = Pattern.compile("^@@ .*\\+(\\d+)(?:,(\\d+))?.*@@");

    @Nonnull
    @Override
    public Result plan(@Nonnull ReviewContext context,
                       @Nonnull String combinedDiff,
                       @Nonnull Set<String> candidateFiles,
                       @Nonnull MetricsRecorder metrics) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(combinedDiff, "combinedDiff");
        Objects.requireNonNull(candidateFiles, "candidateFiles");

        int maxChars = context.getConfig().getMaxCharsPerChunk();
        int maxFiles = context.getConfig().getMaxFilesPerChunk();
        int maxChunks = context.getConfig().getMaxChunks();

        List<ReviewChunk> chunks = new ArrayList<>();
        boolean truncated = false;
        List<String> filesWithoutHunks = new ArrayList<>();

        Map<String, FileDiff> fileDiffs = extractFileDiffs(combinedDiff, candidateFiles);
        if (log.isDebugEnabled()) {
            log.debug("Size-first strategy evaluating {} file(s)", fileDiffs.keySet().size());
        }
        if (Diagnostics.isEnabled()) {
            Diagnostics.log(log, () -> String.format(
                    "Extracted %d file diff(s) for PR #%d: %s",
                    fileDiffs.size(),
                    context.getPullRequest().getId(),
                    fileDiffs.keySet()));
        }

        fileDiffs.entrySet().removeIf(entry -> {
            boolean empty = entry.getValue().hunks.isEmpty();
            if (empty) {
                filesWithoutHunks.add(entry.getKey());
            }
            return empty;
        });

        if (!filesWithoutHunks.isEmpty()) {
            log.warn("Size-first chunk strategy skipping {} file(s) with no textual changes: {}",
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
                    finalizeChunk(context, chunks, builder, buffer, chunkFiles, chunkRanges);

                    if (chunks.size() >= maxChunks) {
                        truncated = true;
                        return new Result(chunks, true, filesWithoutHunks);
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
            finalizeChunk(context, chunks, builder, buffer, chunkFiles, chunkRanges);
        }

        if (chunks.size() > maxChunks) {
            truncated = true;
            chunks = new ArrayList<>(chunks.subList(0, maxChunks));
        }

        if (truncated) {
            log.warn("Chunk planning truncated for PR #{}: reduced to {} chunks (max={})",
                    context.getPullRequest().getId(), chunks.size(), maxChunks);
        }
        return new Result(chunks, truncated, filesWithoutHunks);
    }

    private void finalizeChunk(ReviewContext context,
                               List<ReviewChunk> chunks,
                               ReviewChunk.Builder builder,
                               StringBuilder content,
                               Set<String> chunkFiles,
                               Map<String, LineRange> chunkRanges) {
        builder.content(content.toString());
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
                currentHeader.append(line).append('\n');
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
                currentHeader.append(line).append('\n');
                continue;
            }

            Matcher hunkHeader = HUNK_HEADER.matcher(line);
            if (hunkHeader.matches()) {
                if (currentHunk != null && currentRange != null) {
                    hunks.add(new Hunk(currentHunk.toString(), currentRange));
                }
                currentHunk = new StringBuilder();
                currentHunk.append(line).append('\n');

                int start = Integer.parseInt(hunkHeader.group(1));
                int length = hunkHeader.group(2) != null ? Integer.parseInt(hunkHeader.group(2)) : 1;
                currentRange = LineRange.of(start, start + Math.max(length - 1, 0));
                continue;
            }

            if (currentHunk != null) {
                currentHunk.append(line).append('\n');
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
}
