package com.teknolojikpanda.bitbucket.aicode.core;

import com.atlassian.bitbucket.pull.PullRequest;
import com.teknolojikpanda.bitbucket.aicode.api.AiReviewClient;
import com.teknolojikpanda.bitbucket.aicode.api.MetricsRecorder;
import com.teknolojikpanda.bitbucket.aicode.api.ReviewCanceledException;
import com.teknolojikpanda.bitbucket.aicode.model.ChunkReviewResult;
import com.teknolojikpanda.bitbucket.aicode.model.ReviewChunk;
import com.teknolojikpanda.bitbucket.aicode.model.ReviewConfig;
import com.teknolojikpanda.bitbucket.aicode.model.ReviewContext;
import com.teknolojikpanda.bitbucket.aicode.model.ReviewMode;
import com.teknolojikpanda.bitbucket.aicode.model.ReviewOverview;
import com.teknolojikpanda.bitbucket.aicode.model.ReviewPreparation;
import com.teknolojikpanda.bitbucket.aicode.model.ReviewSummary;
import org.junit.Test;

import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class TwoPassReviewOrchestratorTest {

    @Test
    public void marksSummaryDegradedWhenOneChunkExecutionExplodes() {
        AiReviewClient aiClient = mock(AiReviewClient.class);
        when(aiClient.generateOverview(any(), any())).thenReturn("overview");
        when(aiClient.reviewChunk(any(), any(), any(), any(), any())).thenAnswer(invocation -> {
            ReviewChunk chunk = invocation.getArgument(0);
            if ("chunk-2".equals(chunk.getId())) {
                throw new AssertionError("simulated chunk crash");
            }
            return ChunkReviewResult.builder()
                    .chunk(chunk)
                    .success(true)
                    .build();
        });

        TwoPassReviewOrchestrator orchestrator = new TwoPassReviewOrchestrator(aiClient);
        ReviewSummary summary = orchestrator.runReview(buildPreparation(3), new NoopMetricsRecorder(), null);

        assertTrue(summary.isDegraded());
        assertEquals(1, summary.getFailedChunkCount());
        assertEquals(0, summary.totalCount());
    }

    @Test
    public void interruptedFutureGetCancelsReviewAndPreservesInterruptFlag() {
        AiReviewClient aiClient = mock(AiReviewClient.class);
        when(aiClient.generateOverview(any(), any())).thenReturn("overview");
        when(aiClient.reviewChunk(any(), any(), any(), any(), any())).thenAnswer(invocation -> {
            ReviewChunk chunk = invocation.getArgument(0);
            return ChunkReviewResult.builder()
                    .chunk(chunk)
                    .success(true)
                    .build();
        });

        TwoPassReviewOrchestrator orchestrator = new TwoPassReviewOrchestrator(aiClient);

        Thread.currentThread().interrupt();
        try {
            orchestrator.runReview(buildPreparation(3), new NoopMetricsRecorder(), null);
            fail("Expected ReviewCanceledException");
        } catch (ReviewCanceledException expected) {
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    private ReviewPreparation buildPreparation(int chunkCount) {
        PullRequest pullRequest = mock(PullRequest.class);
        ReviewConfig config = ReviewConfig.builder()
                .primaryModelEndpoint(URI.create("https://8.8.8.8:443"))
                .primaryModel("primary")
                .fallbackModelEndpoint(URI.create("https://1.1.1.1:443"))
                .fallbackModel("fallback")
                .reviewMode(ReviewMode.STANDARD)
                .build();

        ReviewContext context = ReviewContext.builder()
                .pullRequest(pullRequest)
                .config(config)
                .build();

        ReviewOverview overview = ReviewOverview.builder()
                .addFileStats("src/Main.java", new ReviewOverview.FileStats(10, 2, false))
                .build();

        List<ReviewChunk> chunks = new ArrayList<>();
        for (int i = 0; i < chunkCount; i++) {
            chunks.add(ReviewChunk.builder()
                    .id("chunk-" + (i + 1))
                    .index(i)
                    .content("diff-content-" + i)
                    .addFile("src/Main.java")
                    .build());
        }

        return ReviewPreparation.builder()
                .context(context)
                .overview(overview)
                .chunks(chunks)
                .truncated(false)
                .build();
    }

    private static final class NoopMetricsRecorder implements MetricsRecorder {
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
            return new HashMap<>();
        }
    }
}
