package com.teknolojikpanda.bitbucket.aicode.core;

import com.atlassian.bitbucket.pull.PullRequest;
import com.teknolojikpanda.bitbucket.aicode.api.ChunkStrategy;
import com.teknolojikpanda.bitbucket.aicode.api.MetricsRecorder;
import com.teknolojikpanda.bitbucket.aicode.model.LineRange;
import com.teknolojikpanda.bitbucket.aicode.model.ReviewChunk;
import com.teknolojikpanda.bitbucket.aicode.model.ReviewConfig;
import com.teknolojikpanda.bitbucket.aicode.model.ReviewContext;
import com.teknolojikpanda.bitbucket.aicode.model.ReviewOverview;
import com.teknolojikpanda.bitbucket.aicode.model.ReviewProfile;
import com.teknolojikpanda.bitbucket.aicode.model.SeverityLevel;
import org.junit.Before;
import org.junit.Test;

import java.net.URI;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class SizeFirstChunkStrategyTest {

    private SizeFirstChunkStrategy strategy;
    private PullRequest pullRequest;
    private ReviewConfig config;
    private MetricsRecorder metrics;

    @Before
    public void setUp() throws Exception {
        strategy = new SizeFirstChunkStrategy();
        pullRequest = mock(PullRequest.class);
        when(pullRequest.getId()).thenReturn(101L);

        ReviewProfile profile = ReviewProfile.builder()
                .minSeverity(SeverityLevel.MEDIUM)
                .skipGeneratedFiles(false)
                .reviewTests(true)
                .build();

        config = ReviewConfig.builder()
                .primaryModelEndpoint(new URI("http://primary"))
                .primaryModel("primary-model")
                .fallbackModelEndpoint(new URI("http://fallback"))
                .fallbackModel("fallback-model")
                .reviewableExtensions(Set.of("java"))
                .ignorePatterns(Collections.emptyList())
                .ignorePaths(Collections.emptyList())
                .profile(profile)
                .build();

        metrics = new NoOpMetricsRecorder();
    }

    @Test
    public void zeroBasedHunkHeadersClampToOneBasedRanges() {
        String diff = String.join("\n",
                "diff --git a/src/NewFile.java b/src/NewFile.java",
                "new file mode 100644",
                "index 000000000..111111111",
                "--- /dev/null",
                "+++ b/src/NewFile.java",
                "@@ -0,0 +0,0 @@",
                "+public class NewFile {}",
                "");

        ReviewContext context = ReviewContext.builder()
                .pullRequest(pullRequest)
                .config(config)
                .rawDiff(diff)
                .fileStats(Map.of("src/NewFile.java", new ReviewOverview.FileStats(1, 0, false)))
                .collectedAt(Instant.now())
                .build();

        ChunkStrategy.Result result = strategy.plan(
                context,
                diff,
                Set.of("src/NewFile.java"),
                metrics);

        List<ReviewChunk> chunks = result.getChunks();
        assertThat(chunks.size(), is(1));
        List<LineRange> ranges = chunks.get(0).getPrimaryRanges().get("src/NewFile.java");
        assertThat(ranges.get(0).getStart(), equalTo(1));
        assertThat(ranges.get(0).getEnd(), equalTo(1));
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
