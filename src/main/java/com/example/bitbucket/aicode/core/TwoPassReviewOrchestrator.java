package com.example.bitbucket.aicode.core;

import com.atlassian.plugin.spring.scanner.annotation.export.ExportAsService;
import com.example.bitbucket.aicode.api.AiReviewClient;
import com.example.bitbucket.aicode.api.MetricsRecorder;
import com.example.bitbucket.aicode.api.ReviewOrchestrator;
import com.example.bitbucket.aicode.model.ChunkReviewResult;
import com.example.bitbucket.aicode.model.ReviewFinding;
import com.example.bitbucket.aicode.model.ReviewPreparation;
import com.example.bitbucket.aicode.model.ReviewSummary;
import com.example.bitbucket.aicode.model.SeverityLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import javax.inject.Inject;
import javax.inject.Named;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Executes the overview + focused chunk flow and aggregates results.
 */
@Named
@ExportAsService(ReviewOrchestrator.class)
public class TwoPassReviewOrchestrator implements ReviewOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(TwoPassReviewOrchestrator.class);

    private final AiReviewClient aiClient;

    @Inject
    public TwoPassReviewOrchestrator(AiReviewClient aiClient) {
        this.aiClient = Objects.requireNonNull(aiClient, "aiClient");
    }

    @Nonnull
    @Override
    public ReviewSummary runReview(@Nonnull ReviewPreparation preparation, @Nonnull MetricsRecorder metrics) {
        Objects.requireNonNull(preparation, "preparation");
        Objects.requireNonNull(metrics, "metrics");

        if (preparation.getChunks().isEmpty()) {
            return ReviewSummary.builder()
                    .findings(new ArrayList<>())
                    .truncated(preparation.isTruncated())
                    .build();
        }

        Instant overviewStart = metrics.recordStart("ai.overview");
        String overview = aiClient.generateOverview(preparation, metrics);
        metrics.recordEnd("ai.overview", overviewStart);

        int parallelism = Math.max(1, Math.min(
                preparation.getContext().getConfig().getParallelThreads(),
                preparation.getChunks().size()));
        ExecutorService executor = Executors.newFixedThreadPool(parallelism);

        List<Future<ChunkReviewResult>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < preparation.getChunks().size(); i++) {
                final int index = i;
                futures.add(executor.submit(new ChunkTask(index, preparation, overview, metrics)));
            }

            List<ReviewFinding> findings = new ArrayList<>();
            EnumMap<SeverityLevel, Integer> counts = new EnumMap<>(SeverityLevel.class);

            for (Future<ChunkReviewResult> future : futures) {
                ChunkReviewResult result;
                try {
                    result = future.get();
                } catch (Exception e) {
                    log.error("Chunk execution failed: {}", e.getMessage(), e);
                    continue;
                }

                if (!result.isSuccess()) {
                    log.warn("Chunk {} failed: {}", result.getChunk().getId(), result.getError());
                    continue;
                }

                findings.addAll(result.getFindings());
                for (ReviewFinding finding : result.getFindings()) {
                    counts.merge(finding.getSeverity(), 1, Integer::sum);
                }
            }

            ReviewSummary.Builder builder = ReviewSummary.builder()
                    .findings(findings)
                    .truncated(preparation.isTruncated());
            counts.forEach(builder::addCount);
            return builder.build();
        } finally {
            executor.shutdownNow();
        }
    }

    private final class ChunkTask implements Callable<ChunkReviewResult> {
        private final int index;
        private final ReviewPreparation preparation;
        private final String overview;
        private final MetricsRecorder metrics;

        private ChunkTask(int index,
                          ReviewPreparation preparation,
                          String overview,
                          MetricsRecorder metrics) {
            this.index = index;
            this.preparation = preparation;
            this.overview = overview;
            this.metrics = metrics;
        }

        @Override
        public ChunkReviewResult call() {
            Instant start = metrics.recordStart("ai.chunk." + index);
            try {
                ChunkReviewResult result = aiClient.reviewChunk(
                        preparation.getChunks().get(index),
                        overview,
                        preparation.getContext(),
                        metrics);
                metrics.recordEnd("ai.chunk." + index, start);
                return result;
            } catch (Exception ex) {
                metrics.recordEnd("ai.chunk." + index, start);
                log.error("Chunk {} failed: {}", index, ex.getMessage(), ex);
                return ChunkReviewResult.builder()
                        .chunk(preparation.getChunks().get(index))
                        .success(false)
                        .error(ex.getMessage())
                        .build();
            }
        }
    }
}
