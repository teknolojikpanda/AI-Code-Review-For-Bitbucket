package com.teknolojikpanda.bitbucket.aicode.core;

import com.teknolojikpanda.bitbucket.aicode.api.ChunkStrategy;
import com.teknolojikpanda.bitbucket.aicode.api.MetricsRecorder;
import com.teknolojikpanda.bitbucket.aicode.model.LineRange;
import com.teknolojikpanda.bitbucket.aicode.model.ReviewChunk;
import com.teknolojikpanda.bitbucket.aicode.model.ReviewContext;
import com.teknolojikpanda.bitbucket.aicode.util.Diagnostics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import javax.inject.Named;
import java.util.ArrayList;
import java.util.Collections;
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
    private static final Pattern FULL_HUNK_HEADER =
            Pattern.compile("^@@ -(\\d+)(?:,(\\d+))? \\+(\\d+)(?:,(\\d+))? @@(.*)$");
    private static final String MIXED_GROUP_KEY = "__MIXED__";

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
        Map<String, List<LineRange>> chunkRanges = new LinkedHashMap<>();
        String activeGroupKey = null;

        for (FileDiff fileDiff : fileDiffs.values()) {
            boolean filePresentInChunk = chunkFiles.contains(fileDiff.path);
            String groupKey = computeGroupKey(fileDiff.path);
            List<Hunk> hunks = splitHunkIfNeeded(fileDiff.hunks, maxChars);
            for (Hunk hunk : hunks) {
                boolean needsHeader = !filePresentInChunk && !chunkFiles.contains(fileDiff.path);
                String hunkContent = (needsHeader ? fileDiff.header : "") + hunk.content;

                boolean wouldExceedChars = buffer.length() + hunkContent.length() > maxChars;
                boolean wouldExceedFiles = !chunkFiles.contains(fileDiff.path) && chunkFiles.size() >= maxFiles;

                if (wouldExceedChars) {
                    int tolerance = Math.max(maxChars / 10, 512);
                    boolean sameFileContinuation = chunkFiles.contains(fileDiff.path);
                    boolean sharesGroup = activeGroupKey != null && activeGroupKey.equals(groupKey);
                    boolean smallHunk = hunk.content.length() <= tolerance;
                    if ((sameFileContinuation && smallHunk)
                            || (sharesGroup && hunk.content.length() <= tolerance * 2)) {
                        wouldExceedChars = buffer.length() + hunkContent.length() <= maxChars + tolerance;
                    }
                }

                boolean wouldExceed = wouldExceedChars || wouldExceedFiles;

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
                    activeGroupKey = null;
                    filePresentInChunk = false;
                    needsHeader = true;
                    hunkContent = (needsHeader ? fileDiff.header : "") + hunk.content;
                }

                if (needsHeader || !chunkFiles.contains(fileDiff.path)) {
                    chunkFiles.add(fileDiff.path);
                    if (activeGroupKey == null) {
                        activeGroupKey = groupKey;
                    } else if (!Objects.equals(activeGroupKey, groupKey)) {
                        activeGroupKey = MIXED_GROUP_KEY;
                    }
                    filePresentInChunk = true;
                }

                buffer.append(hunkContent);
                chunkRanges.computeIfAbsent(fileDiff.path, ignored -> new ArrayList<>()).add(hunk.range);
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
                               Map<String, List<LineRange>> chunkRanges) {
        builder.content(content.toString());
        builder.files(new ArrayList<>(chunkFiles));
        Map<String, List<LineRange>> ranges = new LinkedHashMap<>();
        chunkRanges.forEach((file, values) ->
                ranges.put(file, new ArrayList<>(values)));
        builder.primaryRanges(ranges);
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

    private List<Hunk> splitHunkIfNeeded(List<Hunk> hunks, int maxChars) {
        List<Hunk> result = new ArrayList<>();
        for (Hunk hunk : hunks) {
            result.addAll(splitSingleHunk(hunk, maxChars));
        }
        return result;
    }

    private List<Hunk> splitSingleHunk(Hunk hunk, int maxChars) {
        if (hunk.content.length() <= maxChars) {
            return Collections.singletonList(hunk);
        }

        String[] lines = hunk.content.split("\n", -1);
        if (lines.length == 0) {
            return Collections.singletonList(hunk);
        }

        Matcher matcher = FULL_HUNK_HEADER.matcher(lines[0]);
        if (!matcher.matches()) {
            log.debug("Unable to split oversized hunk due to unmatched header: {}", lines[0]);
            return Collections.singletonList(hunk);
        }

        int fromStart = parseInt(matcher.group(1), 0);
        int toStart = parseInt(matcher.group(3), 0);
        String headerSuffix = matcher.group(5) == null ? "" : matcher.group(5);

        List<Hunk> parts = new ArrayList<>();
        int currentFromLine = fromStart;
        int currentToLine = toStart;
        int segmentFromStart = currentFromLine;
        int segmentToStart = currentToLine;
        int segmentFromCount = 0;
        int segmentToCount = 0;
        StringBuilder segmentBody = new StringBuilder();

        int headerLength = lines[0].length() + 1;
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i];
            boolean lastLine = (i == lines.length - 1);
            String lineWithNewline = lastLine ? line : line + '\n';
            int prospectiveLength = headerLength + segmentBody.length() + lineWithNewline.length();
            if (segmentBody.length() > 0 && prospectiveLength > maxChars) {
                parts.add(buildSegment(segmentBody.toString(),
                        segmentFromStart,
                        segmentFromCount,
                        segmentToStart,
                        segmentToCount,
                        headerSuffix));
                segmentBody = new StringBuilder();
                segmentFromStart = currentFromLine;
                segmentToStart = currentToLine;
                segmentFromCount = 0;
                segmentToCount = 0;
            }

            segmentBody.append(lineWithNewline);
            char prefix = line.isEmpty() ? ' ' : line.charAt(0);
            switch (prefix) {
                case ' ':
                    currentFromLine++;
                    currentToLine++;
                    segmentFromCount++;
                    segmentToCount++;
                    break;
                case '-':
                    currentFromLine++;
                    segmentFromCount++;
                    break;
                case '+':
                    currentToLine++;
                    segmentToCount++;
                    break;
                default:
                    currentFromLine++;
                    currentToLine++;
                    segmentFromCount++;
                    segmentToCount++;
                    break;
            }
        }

        if (segmentBody.length() > 0) {
            parts.add(buildSegment(segmentBody.toString(),
                    segmentFromStart,
                    segmentFromCount,
                    segmentToStart,
                    segmentToCount,
                    headerSuffix));
        }

        if (parts.isEmpty()) {
            return Collections.singletonList(hunk);
        }

        int index = 0;
        for (Hunk part : parts) {
            if (part.range.getStart() <= 0) {
                parts.set(index, new Hunk(part.content, LineRange.singleLine(Math.max(1, toStart))));
            }
            index++;
        }
        return parts;
    }

    private Hunk buildSegment(String body,
                              int fromStart,
                              int fromCount,
                              int toStart,
                              int toCount,
                              String suffix) {
        String header = buildHunkHeader(fromStart, fromCount, toStart, toCount, suffix);
        LineRange range = LineRange.of(
                Math.max(1, toStart),
                Math.max(Math.max(1, toStart), toStart + Math.max(toCount - 1, 0)));
        return new Hunk(header + '\n' + body, range);
    }

    private String buildHunkHeader(int fromStart, int fromCount, int toStart, int toCount, String suffix) {
        StringBuilder builder = new StringBuilder();
        builder.append("@@ -").append(fromStart);
        if (fromCount != 1) {
            builder.append(',').append(Math.max(fromCount, 0));
        }
        builder.append(" +").append(toStart);
        if (toCount != 1) {
            builder.append(',').append(Math.max(toCount, 0));
        }
        builder.append(" @@");
        if (suffix != null && !suffix.isEmpty()) {
            builder.append(suffix);
        }
        return builder.toString();
    }

    private String computeGroupKey(String path) {
        if (path == null || path.isEmpty()) {
            return MIXED_GROUP_KEY;
        }
        int lastSlash = path.lastIndexOf('/') >= 0 ? path.lastIndexOf('/') : path.lastIndexOf('\\');
        String directory = lastSlash >= 0 ? path.substring(0, lastSlash) : "";
        String fileName = lastSlash >= 0 ? path.substring(lastSlash + 1) : path;
        int dotIndex = fileName.lastIndexOf('.');
        String extension = dotIndex >= 0 ? fileName.substring(dotIndex) : "";
        String stem = dotIndex >= 0 ? fileName.substring(0, dotIndex) : fileName;
        String normalizedStem = stem.replaceAll("(?i)(Test|Spec|IT)$", "");
        if (normalizedStem.isEmpty()) {
            normalizedStem = stem;
        }
        return directory + "::" + normalizedStem + extension;
    }

    private int parseInt(String value, int defaultValue) {
        try {
            return value == null ? defaultValue : Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return defaultValue;
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
