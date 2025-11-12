package com.teknolojikpanda.bitbucket.aicode.core;

import com.atlassian.plugin.spring.scanner.annotation.export.ExportAsService;
import com.teknolojikpanda.bitbucket.aicode.api.AiReviewClient;
import com.teknolojikpanda.bitbucket.aicode.api.ChunkProgressListener;
import com.teknolojikpanda.bitbucket.aicode.api.MetricsRecorder;
import com.teknolojikpanda.bitbucket.aicode.api.ReviewCanceledException;
import com.teknolojikpanda.bitbucket.aicode.api.ReviewOrchestrator;
import com.teknolojikpanda.bitbucket.aicode.model.ChunkReviewResult;
import com.teknolojikpanda.bitbucket.aicode.model.ReviewFinding;
import com.teknolojikpanda.bitbucket.aicode.model.ReviewChunk;
import com.teknolojikpanda.bitbucket.aicode.model.ReviewPreparation;
import com.teknolojikpanda.bitbucket.aicode.model.ReviewSummary;
import com.teknolojikpanda.bitbucket.aicode.model.SeverityLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
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
    public ReviewSummary runReview(@Nonnull ReviewPreparation preparation,
                                   @Nonnull MetricsRecorder metrics,
                                   @Nullable ChunkProgressListener chunkListener) {
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

        int totalChunks = preparation.getChunks().size();
        int parallelism = Math.max(1, Math.min(
                preparation.getContext().getConfig().getParallelThreads(),
                totalChunks));
        if (log.isInfoEnabled()) {
            log.info("Starting AI review with {} chunk(s) (parallelism = {})", totalChunks, parallelism);
        }
        ExecutorService executor = Executors.newFixedThreadPool(parallelism);

        List<Future<ChunkReviewResult>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < preparation.getChunks().size(); i++) {
                final int index = i;
                futures.add(executor.submit(new ChunkTask(index, totalChunks, preparation, overview, metrics, chunkListener)));
            }

            List<ReviewFinding> findings = new ArrayList<>();
            EnumMap<SeverityLevel, Integer> counts = new EnumMap<>(SeverityLevel.class);

            for (Future<ChunkReviewResult> future : futures) {
                ChunkReviewResult result;
                try {
                    result = future.get();
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new ReviewCanceledException(null, "Review execution interrupted");
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
        private final int total;
        private final ReviewPreparation preparation;
        private final String overview;
        private final MetricsRecorder metrics;
        private final ChunkProgressListener chunkListener;

        private ChunkTask(int index,
                          int total,
                          ReviewPreparation preparation,
                          String overview,
                          MetricsRecorder metrics,
                          @Nullable ChunkProgressListener chunkListener) {
            this.index = index;
            this.total = total;
            this.preparation = preparation;
            this.overview = overview;
            this.metrics = metrics;
            this.chunkListener = chunkListener;
        }

        @Override
        public ChunkReviewResult call() {
            if (Thread.currentThread().isInterrupted()) {
                throw new ReviewCanceledException(null, "Review execution interrupted");
            }
            ReviewChunk chunk = preparation.getChunks().get(index);
            if (log.isInfoEnabled()) {
                log.info("Chunk {}/{} [{}] started (files={}, chars={})",
                        index + 1,
                        total,
                        chunk.getId(),
                        chunk.getFiles().size(),
                        chunk.getContent() != null ? chunk.getContent().length() : 0);
            }
            notifyChunkStarted(chunk);
            metrics.increment("chunks.started");
            Instant start = metrics.recordStart("ai.chunk." + index);
            boolean success = false;
            try {
                ChunkReviewResult result = aiClient.reviewChunk(
                        chunk,
                        overview,
                        preparation.getContext(),
                        metrics);
                metrics.recordEnd("ai.chunk." + index, start);
                if (log.isInfoEnabled()) {
                    if (result.isSuccess()) {
                        metrics.increment("chunks.succeeded");
                        log.info("Chunk {}/{} [{}] completed with {} finding(s)",
                                index + 1,
                                total,
                                chunk.getId(),
                                result.getFindings().size());
                        success = true;
                    } else {
                        metrics.increment("chunks.failed");
                        log.warn("Chunk {}/{} [{}] returned failure: {}",
                                index + 1,
                                total,
                                chunk.getId(),
                                result.getError());
                    }
                }
                return result;
            } catch (Exception ex) {
                if (ex instanceof InterruptedException || ex.getCause() instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                    throw new ReviewCanceledException(null, "Review execution interrupted");
                }
                metrics.recordEnd("ai.chunk." + index, start);
                metrics.increment("chunks.failed");
                log.error("Chunk {}/{} [{}] failed: {}",
                        index + 1,
                        total,
                        chunk.getId(),
                        ex.getMessage(),
                        ex);
                return ChunkReviewResult.builder()
                        .chunk(chunk)
                        .success(false)
                        .error(ex.getMessage())
                        .build();
            } finally {
                notifyChunkCompleted(chunk, success);
            }
        }

        private void notifyChunkStarted(ReviewChunk chunk) {
            if (chunkListener == null) {
                return;
            }
            try {
                chunkListener.onChunkStarted(chunk, index, total);
            } catch (Exception ex) {
                log.debug("Chunk start listener failed for {}: {}", chunk.getId(), ex.getMessage(), ex);
            }
        }

        private void notifyChunkCompleted(ReviewChunk chunk, boolean success) {
            if (chunkListener == null) {
                return;
            }
            try {
                chunkListener.onChunkCompleted(chunk, index, total, success);
            } catch (Exception ex) {
                log.debug("Chunk completion listener failed for {}: {}", chunk.getId(), ex.getMessage(), ex);
            }
        }
    }
}
