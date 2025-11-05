package com.teknolojikpanda.bitbucket.aireviewer.perf;

import com.teknolojikpanda.bitbucket.aicode.model.ReviewOverview;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Micro-benchmark that exercises the diff streaming accumulator logic used by {@link com.teknolojikpanda.bitbucket.aicode.core.DefaultDiffProvider}.
 * Generates synthetic unified diffs and feeds them to the accumulator in streamed chunks to capture parsing overhead.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 2, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
public class DiffStreamingBenchmark {

    @Param({"1", "4"})
    public int chunkMultiplier;

    private byte[][] diffChunks;
    private int maxBytes;

    @Setup(Level.Trial)
    public void setUpDiff() {
        SyntheticDiff diff = SyntheticDiff.generate(64, 3, 60);
        byte[] payload = diff.diff.getBytes(StandardCharsets.UTF_8);
        int chunkSize = 4 * 1024 * chunkMultiplier;
        int chunkCount = (payload.length + chunkSize - 1) / chunkSize;
        diffChunks = new byte[chunkCount][];
        for (int i = 0; i < chunkCount; i++) {
            int start = i * chunkSize;
            int end = Math.min(payload.length, start + chunkSize);
            int len = end - start;
            byte[] chunk = new byte[len];
            System.arraycopy(payload, start, chunk, 0, len);
            diffChunks[i] = chunk;
        }
        maxBytes = payload.length * 2;
    }

    @Benchmark
    public DiffStreamingResult streamAccumulator() {
        StreamingDiffAccumulatorHarness accumulator = new StreamingDiffAccumulatorHarness(maxBytes);
        for (byte[] chunk : diffChunks) {
            accumulator.write(chunk, 0, chunk.length);
        }
        accumulator.finish();
        StreamingDiffAccumulatorHarness.DiffBundle bundle = accumulator.toBundle();
        return new DiffStreamingResult(bundle.bytes, bundle.lines, bundle.fileStats.size());
    }

    public static final class DiffStreamingResult {
        public final long bytes;
        public final int lines;
        public final int files;

        public DiffStreamingResult(long bytes, int lines, int files) {
            this.bytes = bytes;
            this.lines = lines;
            this.files = files;
        }
    }

    private static final class SyntheticDiff {
        final String diff;

        private SyntheticDiff(String diff) {
            this.diff = diff;
        }

        static SyntheticDiff generate(int files, int hunksPerFile, int linesPerHunk) {
            StringBuilder diffBuilder = new StringBuilder(files * hunksPerFile * linesPerHunk * 12);
            for (int fileIndex = 0; fileIndex < files; fileIndex++) {
                String path = String.format(Locale.ENGLISH, "src/main/java/generated/DiffedFile%03d.java", fileIndex);
                diffBuilder.append("diff --git a/").append(path).append(" b/").append(path).append('\n');
                diffBuilder.append("--- a/").append(path).append('\n');
                diffBuilder.append("+++ b/").append(path).append('\n');

                for (int hunkIndex = 0; hunkIndex < hunksPerFile; hunkIndex++) {
                    int startLine = hunkIndex * (linesPerHunk + 3) + 1;
                    String header = String.format(Locale.ENGLISH, "@@ -%d,%d +%d,%d @@\n",
                            startLine, linesPerHunk, startLine, linesPerHunk);
                    diffBuilder.append(header);
                    for (int line = 0; line < linesPerHunk; line++) {
                        diffBuilder.append('-').append("old line ").append(line).append(' ').append(path).append('\n');
                        diffBuilder.append('+').append("new line ").append(line).append(' ').append(path).append('\n');
                    }
                    diffBuilder.append(' ').append("context line ").append(hunkIndex).append(' ').append(path).append('\n');
                }
            }
            return new SyntheticDiff(diffBuilder.toString());
        }
    }

    /**
     * Lightweight copy of {@link com.teknolojikpanda.bitbucket.aicode.core.DefaultDiffProvider.StreamingDiffAccumulator}
     * adapted for benchmarking purposes (no logging, public visibility).
     */
    private static final class StreamingDiffAccumulatorHarness extends java.io.OutputStream {
        private static final java.util.regex.Pattern DIFF_HEADER_PATTERN = java.util.regex.Pattern.compile("^diff --git\\s+(.+?)\\s+(.+)$");

        private final long maxBytes;
        private final Map<String, MutableFileState> files = new LinkedHashMap<>();
        private final StringBuilder buffer = new StringBuilder();
        private final StringBuilder rawDiff = new StringBuilder();
        private long totalBytes;
        private int totalLines;
        private String currentFileKey;
        private MutableFileState currentFile;

        StreamingDiffAccumulatorHarness(long maxBytes) {
            this.maxBytes = maxBytes;
        }

        @Override
        public void write(int b) {
            write(new byte[]{(byte) b}, 0, 1);
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

        StreamingDiffAccumulatorHarness.DiffBundle toBundle() {
            Map<String, ReviewOverview.FileStats> stats = new LinkedHashMap<>();
            for (Map.Entry<String, MutableFileState> entry : files.entrySet()) {
                MutableFileState state = entry.getValue();
                stats.put(entry.getKey(), new ReviewOverview.FileStats(state.additions, state.deletions, state.binary));
            }
            return new DiffBundle(rawDiff.toString(), stats, totalBytes, totalLines);
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

            java.util.regex.Matcher matcher = DIFF_HEADER_PATTERN.matcher(effective);
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

        private void switchCurrentFile(String rawLine, java.util.regex.Matcher matcher) {
            String pathA = normalizeDiffPath(matcher.group(1));
            String pathB = normalizeDiffPath(matcher.group(2));
            String key = (pathB == null || isDevNull(pathB)) ? pathA : pathB;
            if (key == null || key.isEmpty()) {
                key = pathA != null ? pathA : pathB;
            }
            if (key == null || key.isEmpty()) {
                currentFileKey = null;
                currentFile = null;
                return;
            }
            currentFileKey = key;
            currentFile = files.computeIfAbsent(currentFileKey, __ -> new MutableFileState());
            currentFile.append(rawLine);
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
            final Map<String, ReviewOverview.FileStats> fileStats;
            final long bytes;
            final int lines;

            DiffBundle(String rawDiff,
                       Map<String, ReviewOverview.FileStats> fileStats,
                       long bytes,
                       int lines) {
                this.rawDiff = rawDiff;
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
    }
}
