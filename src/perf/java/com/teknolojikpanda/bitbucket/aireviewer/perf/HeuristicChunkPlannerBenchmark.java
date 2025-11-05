package com.teknolojikpanda.bitbucket.aireviewer.perf;

import com.atlassian.bitbucket.pull.PullRequest;
import com.teknolojikpanda.bitbucket.aicode.api.MetricsRecorder;
import com.teknolojikpanda.bitbucket.aicode.core.HeuristicChunkPlanner;
import com.teknolojikpanda.bitbucket.aicode.core.SizeFirstChunkStrategy;
import com.teknolojikpanda.bitbucket.aicode.model.ReviewConfig;
import com.teknolojikpanda.bitbucket.aicode.model.ReviewContext;
import com.teknolojikpanda.bitbucket.aicode.model.ReviewOverview;
import com.teknolojikpanda.bitbucket.aicode.model.ReviewProfile;
import com.teknolojikpanda.bitbucket.aicode.model.ReviewPreparation;
import com.teknolojikpanda.bitbucket.aicode.model.SeverityLevel;
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

import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Micro-benchmark for {@link HeuristicChunkPlanner}. Generates synthetic diffs with many files/hunks
 * and measures the time spent to prepare review chunks.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 2, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
public class HeuristicChunkPlannerBenchmark {

    @Param({"32", "96"})
    public int fileCount;

    private HeuristicChunkPlanner planner;
    private ReviewContext context;
    private MetricsRecorder metricsRecorder;

    @Setup(Level.Trial)
    public void setUpPlanner() {
        planner = new HeuristicChunkPlanner(new SizeFirstChunkStrategy());
        metricsRecorder = new NoopMetricsRecorder();
    }

    @Setup(Level.Iteration)
    public void setUpContext() {
        SyntheticDiff diff = SyntheticDiff.generate(fileCount, 3, 80);
        context = ReviewContext.builder()
                .pullRequest(new StubPullRequest(42L))
                .config(buildConfig())
                .rawDiff(diff.diff)
                .fileStats(diff.fileStats)
                .fileDiffs(diff.fileDiffs)
                .fileMetadata(diff.fileMetadata)
                .collectedAt(Instant.now())
                .build();
    }

    private ReviewConfig buildConfig() {
        ReviewProfile profile = ReviewProfile.builder()
                .minSeverity(SeverityLevel.MEDIUM)
                .requireApprovalFor(new LinkedHashSet<>(Arrays.asList(SeverityLevel.HIGH, SeverityLevel.CRITICAL)))
                .skipGeneratedFiles(false)
                .reviewTests(true)
                .maxIssuesPerFile(100)
                .build();
        try {
            URI endpoint = new URI("http://localhost:11434");
            return ReviewConfig.builder()
                    .primaryModelEndpoint(endpoint)
                    .primaryModel("benchmark-model")
                    .fallbackModelEndpoint(endpoint)
                    .fallbackModel("benchmark-model-fallback")
                    .maxCharsPerChunk(60_000)
                    .maxFilesPerChunk(6)
                    .maxChunks(40)
                    .parallelThreads(4)
                    .reviewableExtensions(new LinkedHashSet<>(Arrays.asList("java", "kt", "xml")))
                    .ignorePatterns(Collections.emptyList())
                    .ignorePaths(Collections.emptyList())
                    .maxDiffBytes(15_000_000)
                    .profile(profile)
                    .build();
        } catch (Exception e) {
            throw new IllegalStateException("Unable to create benchmark config", e);
        }
    }

    @Benchmark
    public ReviewPreparation planLargeDiff() {
        return planner.prepare(context, metricsRecorder);
    }

    private static final class NoopMetricsRecorder implements MetricsRecorder {
        @Override
        public Instant recordStart(String key) {
            return Instant.now();
        }

        @Override
        public void recordEnd(String key, Instant start) { }

        @Override
        public void increment(String key) { }

        @Override
        public void recordMetric(String key, Object value) { }

        @Override
        public void addListEntry(String key, Map<String, Object> value) { }

        @Override
        public Map<String, Object> snapshot() {
            return Collections.emptyMap();
        }
    }

    private static final class StubPullRequest implements PullRequest {
        private final long id;

        StubPullRequest(long id) {
            this.id = id;
        }

        @Override
        public long getId() {
            return id;
        }

        @Override
        public int getVersion() {
            return 1;
        }

        @Override
        public java.util.Date getUpdatedDate() {
            return new java.util.Date();
        }

        @Override
        public java.util.Date getCreatedDate() {
            return new java.util.Date();
        }

        @Override
        public boolean isClosed() {
            return false;
        }

        @Override
        public boolean isOpen() {
            return true;
        }

        @Override
        public com.atlassian.bitbucket.pull.PullRequestRef getFromRef() {
            return null;
        }

        @Override
        public com.atlassian.bitbucket.pull.PullRequestRef getToRef() {
            return null;
        }

        @Override
        public String getTitle() {
            return "benchmark";
        }

        @Override
        public String getDescription() {
            return "synthetic benchmark";
        }

        @Override
        public com.atlassian.bitbucket.pull.PullRequestState getState() {
            return com.atlassian.bitbucket.pull.PullRequestState.OPEN;
        }

        @Override
        public boolean isLocked() {
            return false;
        }

        @Override
        public boolean isDraft() {
            return false;
        }

        @Override
        public boolean isCrossRepository() {
            return false;
        }

        @Override
        public boolean isClosedCleanly() {
            return false;
        }

        @Override
        public java.util.Set<com.atlassian.bitbucket.pull.PullRequestParticipant> getReviewers() {
            return java.util.Collections.emptySet();
        }

        @Override
        public java.util.Set<com.atlassian.bitbucket.pull.PullRequestParticipant> getParticipants() {
            return java.util.Collections.emptySet();
        }

        @Override
        public com.atlassian.bitbucket.pull.PullRequestParticipant getAuthor() {
            return null;
        }

        @Override
        public java.util.Date getClosedDate() {
            return null;
        }

        @Override
        public com.atlassian.bitbucket.pull.PullRequestRef getMergedRef() {
            return null;
        }

        @Override
        public java.util.Map<String, Object> getProperties() {
            return Collections.emptyMap();
        }

        @Override
        public void setProperties(java.util.Map<String, Object> map) { }
    }

    private static final class SyntheticDiff {
        final String diff;
        final Map<String, ReviewOverview.FileStats> fileStats;
        final Map<String, String> fileDiffs;
        final Map<String, com.teknolojikpanda.bitbucket.aicode.model.ReviewFileMetadata> fileMetadata;

        private SyntheticDiff(String diff,
                               Map<String, ReviewOverview.FileStats> fileStats,
                               Map<String, String> fileDiffs,
                               Map<String, com.teknolojikpanda.bitbucket.aicode.model.ReviewFileMetadata> fileMetadata) {
            this.diff = diff;
            this.fileStats = fileStats;
            this.fileDiffs = fileDiffs;
            this.fileMetadata = fileMetadata;
        }

        static SyntheticDiff generate(int files, int hunksPerFile, int linesPerHunk) {
            StringBuilder diffBuilder = new StringBuilder(files * hunksPerFile * linesPerHunk * 12);
            Map<String, ReviewOverview.FileStats> stats = new LinkedHashMap<>();
            Map<String, String> fileDiffs = new HashMap<>();
            Map<String, com.teknolojikpanda.bitbucket.aicode.model.ReviewFileMetadata> metadata = new HashMap<>();

            for (int fileIndex = 0; fileIndex < files; fileIndex++) {
                String path = String.format(Locale.ENGLISH, "src/main/java/generated/File%03d.java", fileIndex);
                diffBuilder.append("diff --git a/").append(path).append(" b/").append(path).append('\n');
                diffBuilder.append("--- a/").append(path).append('\n');
                diffBuilder.append("+++ b/").append(path).append('\n');

                StringBuilder fileDiff = new StringBuilder();
                int additions = 0;
                int deletions = 0;

                for (int hunkIndex = 0; hunkIndex < hunksPerFile; hunkIndex++) {
                    int startLine = hunkIndex * (linesPerHunk + 5) + 1;
                    String header = String.format(Locale.ENGLISH, "@@ -%d,%d +%d,%d @@\n",
                            startLine, linesPerHunk, startLine, linesPerHunk);
                    diffBuilder.append(header);
                    fileDiff.append(header);

                    for (int line = 0; line < linesPerHunk; line++) {
                        String removal = "- old line " + line + " file" + fileIndex + '\n';
                        String addition = "+ new line " + line + " file" + fileIndex + '\n';
                        diffBuilder.append(removal).append(addition);
                        fileDiff.append(removal).append(addition);
                    }

                    String context = " context line " + hunkIndex + " file" + fileIndex + '\n';
                    diffBuilder.append(context);
                    fileDiff.append(context);

                    additions += linesPerHunk;
                    deletions += linesPerHunk;
                }

                stats.put(path, new ReviewOverview.FileStats(additions, deletions, false));
                fileDiffs.put(path, fileDiff.toString());
                metadata.put(path, com.teknolojikpanda.bitbucket.aicode.model.ReviewFileMetadata.builder()
                        .path(path)
                        .directory("src/main/java/generated")
                        .extension("java")
                        .additions(additions)
                        .deletions(deletions)
                        .binary(false)
                        .testFile(false)
                        .build());
            }

            return new SyntheticDiff(diffBuilder.toString(), stats, fileDiffs, metadata);
        }
    }
}
