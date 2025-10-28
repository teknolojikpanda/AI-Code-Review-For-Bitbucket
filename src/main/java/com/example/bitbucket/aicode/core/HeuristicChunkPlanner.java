package com.example.bitbucket.aicode.core;

import com.atlassian.plugin.spring.scanner.annotation.export.ExportAsService;
import com.example.bitbucket.aicode.api.ChunkPlanner;
import com.example.bitbucket.aicode.api.ChunkStrategy;
import com.example.bitbucket.aicode.api.MetricsRecorder;
import com.example.bitbucket.aicode.model.ReviewContext;
import com.example.bitbucket.aicode.model.ReviewOverview;
import com.example.bitbucket.aicode.model.ReviewPreparation;
import com.example.bitbucket.aicode.util.Diagnostics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import javax.annotation.Nonnull;
import javax.inject.Inject;
import javax.inject.Named;
import java.time.Instant;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Splits diffs into AI-sized chunks using diff hunk heuristics.
 */
@Named
@ExportAsService(ChunkPlanner.class)
public class HeuristicChunkPlanner implements ChunkPlanner {

    private static final Logger log = LoggerFactory.getLogger(HeuristicChunkPlanner.class);

    private static final Set<String> TEST_KEYWORDS = new HashSet<>(Arrays.asList("test", "spec", "fixture"));

    private final ChunkStrategy chunkStrategy;

    @Inject
    public HeuristicChunkPlanner(ChunkStrategy chunkStrategy) {
        this.chunkStrategy = Objects.requireNonNull(chunkStrategy, "chunkStrategy");
    }

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
        ChunkStrategy.Result result = chunkStrategy.plan(context, diff, filesToReview, metrics);
        metrics.recordEnd("chunk.plan", planStart);
        metrics.recordMetric("chunks.count", result.getChunks().size());
        metrics.recordMetric("chunks.truncated", result.isTruncated());
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
                .chunks(result.getChunks())
                .truncated(result.isTruncated())
                .skippedFiles(result.getSkippedFiles())
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

}
