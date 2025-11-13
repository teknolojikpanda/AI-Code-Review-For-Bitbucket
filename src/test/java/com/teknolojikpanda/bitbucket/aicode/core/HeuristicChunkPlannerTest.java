package com.teknolojikpanda.bitbucket.aicode.core;

import com.atlassian.bitbucket.pull.PullRequest;
import com.teknolojikpanda.bitbucket.aicode.api.ChunkStrategy;
import com.teknolojikpanda.bitbucket.aicode.api.MetricsRecorder;
import com.teknolojikpanda.bitbucket.aicode.model.*;
import org.junit.Before;
import org.junit.Test;

import java.net.URI;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class HeuristicChunkPlannerTest {

    private PullRequest pullRequest;
    private CapturingStrategy strategy;
    private MetricsRecorder metrics;

    @Before
    public void setUp() throws Exception {
        pullRequest = mock(PullRequest.class);
        when(pullRequest.getId()).thenReturn(42L);
        strategy = new CapturingStrategy();
        metrics = new NoOpMetricsRecorder();
    }

    @Test
    public void skipGeneratedFilesFiltersGeneratedPaths() throws Exception {
        ReviewProfile profile = ReviewProfile.builder()
                .skipGeneratedFiles(true)
                .reviewTests(true)
                .build();
        ReviewConfig config = baseConfig(profile);

        Map<String, ReviewOverview.FileStats> stats = new LinkedHashMap<>();
        stats.put("src/main/java/App.java", new ReviewOverview.FileStats(10, 0, false));
        stats.put("src/generated/java/AppGenerated.java", new ReviewOverview.FileStats(5, 0, false));

        ReviewContext context = baseContext(config, stats);

        HeuristicChunkPlanner planner = new HeuristicChunkPlanner(strategy);
        planner.prepare(context, metrics);

        assertThat(strategy.getLastCandidates(), contains("src/main/java/App.java"));
    }

    @Test
    public void skipTestsFiltersTestPathsWhenDisabled() throws Exception {
        ReviewProfile profile = ReviewProfile.builder()
                .skipGeneratedFiles(false)
                .reviewTests(false) // skip tests
                .build();
        ReviewConfig config = baseConfig(profile);

        Map<String, ReviewOverview.FileStats> stats = new LinkedHashMap<>();
        stats.put("src/main/java/App.java", new ReviewOverview.FileStats(10, 0, false));
        stats.put("src/test/java/AppTest.java", new ReviewOverview.FileStats(7, 0, false));

        ReviewContext context = baseContext(config, stats);

        HeuristicChunkPlanner planner = new HeuristicChunkPlanner(strategy);
        planner.prepare(context, metrics);

        assertThat(strategy.getLastCandidates(), contains("src/main/java/App.java"));
    }

    private ReviewContext baseContext(ReviewConfig config,
                                      Map<String, ReviewOverview.FileStats> stats) {
        return ReviewContext.builder()
                .pullRequest(pullRequest)
                .config(config)
                .rawDiff("diff --git a/App.java b/App.java")
                .fileStats(stats)
                .fileDiffs(Collections.emptyMap())
                .fileMetadata(Collections.emptyMap())
                .collectedAt(Instant.now())
                .build();
    }

    private ReviewConfig baseConfig(ReviewProfile profile) throws Exception {
        return ReviewConfig.builder()
                .primaryModelEndpoint(new URI("http://primary"))
                .primaryModel("primary-model")
                .fallbackModelEndpoint(new URI("http://fallback"))
                .fallbackModel("fallback-model")
                .reviewableExtensions(Set.of("java"))
                .ignorePatterns(Collections.emptyList())
                .ignorePaths(Collections.emptyList())
                .profile(profile)
                .build();
    }

    private static final class CapturingStrategy implements ChunkStrategy {
        private Set<String> lastCandidates = Collections.emptySet();

        @Override
        public Result plan(ReviewContext context,
                           String combinedDiff,
                           Set<String> candidateFiles,
                           MetricsRecorder metrics) {
            this.lastCandidates = Set.copyOf(candidateFiles);
            return new Result(Collections.emptyList(), false, Collections.emptyList());
        }

        Set<String> getLastCandidates() {
            return lastCandidates;
        }
    }

    private static final class NoOpMetricsRecorder implements MetricsRecorder {
        @Override
        public Instant recordStart(String key) {
            return Instant.now();
        }

        @Override
        public void recordEnd(String key, Instant start) {
        }

        @Override
        public void increment(String key) {
        }

        @Override
        public void recordMetric(String key, Object value) {
        }

        @Override
        public void addListEntry(String key, Map<String, Object> value) {
        }

        @Override
        public Map<String, Object> snapshot() {
            return Collections.emptyMap();
        }
    }
}
