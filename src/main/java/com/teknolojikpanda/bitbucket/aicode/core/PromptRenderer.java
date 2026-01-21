package com.teknolojikpanda.bitbucket.aicode.core;

import com.teknolojikpanda.bitbucket.aicode.model.LineRange;
import com.teknolojikpanda.bitbucket.aicode.model.PromptTemplates;
import com.teknolojikpanda.bitbucket.aicode.model.ReviewConfig;
import com.teknolojikpanda.bitbucket.aicode.model.ReviewContext;
import com.teknolojikpanda.bitbucket.aicode.model.ReviewOverview;
import com.teknolojikpanda.bitbucket.aicode.model.ReviewPreparation;
import com.teknolojikpanda.bitbucket.aicode.model.ReviewProfile;
import com.teknolojikpanda.bitbucket.aicode.model.ReviewMode;
import com.teknolojikpanda.bitbucket.aicode.model.ReviewChunk;
import com.teknolojikpanda.bitbucket.aicode.model.ReviewFileMetadata;

import javax.annotation.Nonnull;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class PromptRenderer {

    private PromptRenderer() {
    }

    static String renderOverview(@Nonnull ReviewPreparation preparation,
                                 @Nonnull PromptTemplates templates) {
        ReviewOverview overview = preparation.getOverview();
        String header = String.format(
                templates.getOverviewTemplate(),
                preparation.getContext().getPullRequest().getToRef().getRepository().getProject().getKey(),
                preparation.getContext().getPullRequest().getToRef().getRepository().getSlug(),
                preparation.getContext().getPullRequest().getId(),
                preparation.getContext().getPullRequest().getTitle(),
                overview.getTotalFiles(),
                overview.getTotalAdditions(),
                overview.getTotalDeletions());

        StringBuilder builder = new StringBuilder(header).append('\n');
        overview.getFileStats().forEach((path, stats) -> builder.append(String.format(
                templates.getOverviewFileEntryTemplate(),
                path,
                stats.getAdditions(),
                stats.getDeletions())));
        return builder.toString();
    }

    static String renderImpactSummary(@Nonnull ReviewPreparation preparation,
                                      @Nonnull PromptTemplates templates,
                                      @Nonnull String overview) {
        ReviewContext context = preparation.getContext();
        if (context == null || context.getConfig() == null) {
            return overview;
        }
        String rawDiff = context != null ? context.getRawDiff() : "";
        String diffContent = rawDiff != null ? rawDiff : "";
        int limit = resolveImpactDiffLimit(context != null ? context.getConfig() : null);
        String truncated = diffContent.length() > limit
                ? diffContent.substring(0, limit) + "\n...(diff truncated)"
                : diffContent;

        Map<String, String> placeholders = new LinkedHashMap<>();
        placeholders.put("{{OVERVIEW}}", overview);
        placeholders.put("{{REVIEW_MODE}}", context.getConfig().getReviewMode().getDisplayName());
        placeholders.put("{{MODE_INSTRUCTIONS}}", context.getConfig().getReviewMode().getPromptInstructions());
        placeholders.put("{{RAW_DIFF}}", truncated);
        return applyPlaceholders(templates.getImpactSummaryTemplate(), placeholders);
    }

    static String renderChunkInstructions(@Nonnull PromptTemplates templates,
                                          @Nonnull ReviewConfig config,
                                          @Nonnull ReviewContext context,
                                          @Nonnull ReviewChunk chunk,
                                          @Nonnull String overview,
                                          @Nonnull String impactSummary,
                                          @Nonnull String annotatedDiff) {
        Map<String, String> placeholders = new LinkedHashMap<>();
        placeholders.put("{{OVERVIEW}}", overview);
        ReviewProfile profile = config.getProfile();
        placeholders.put("{{MIN_SEVERITY}}", profile.getMinSeverity().name().toLowerCase());
    placeholders.put("{{REVIEW_MODE}}", config.getReviewMode().getDisplayName());
    placeholders.put("{{MODE_INSTRUCTIONS}}", config.getReviewMode().getPromptInstructions());
        placeholders.put("{{IMPACT_SUMMARY}}", impactSummary != null ? impactSummary : "");
        placeholders.put("{{CHUNK_CONTEXT}}", buildChunkContext(context, chunk));
        placeholders.put("{{ANNOTATED_DIFF}}", annotatedDiff);
        return applyPlaceholders(templates.getChunkInstructionsTemplate(), placeholders);
    }

    private static int resolveImpactDiffLimit(ReviewConfig config) {
        if (config == null) {
            return 60000;
        }
        ReviewMode mode = config.getReviewMode();
        if (mode == ReviewMode.FULL) {
            return 120000;
        }
        if (mode == ReviewMode.DEEP) {
            return 80000;
        }
        return 60000;
    }

    private static String buildChunkContext(ReviewContext context, ReviewChunk chunk) {
        StringBuilder builder = new StringBuilder();
        appendGlobalContext(context, builder);
        chunk.getFiles().forEach(path -> {
            ReviewFileMetadata meta = context.getFileMetadata().get(path);
            builder.append("- ").append(path);
            if (meta != null) {
                builder.append(" (language=")
                        .append(meta.getLanguage() != null ? meta.getLanguage() : "unknown")
                        .append(", +")
                        .append(meta.getAdditions())
                        .append("/-")
                        .append(meta.getDeletions());
                if (meta.isTestFile()) {
                    builder.append(", test");
                }
                if (meta.isBinary()) {
                    builder.append(", binary");
                }
                builder.append(")");
            }
            List<LineRange> ranges = chunk.getPrimaryRanges().get(path);
            if (ranges != null && !ranges.isEmpty()) {
                builder.append(" lines ");
                for (int i = 0; i < ranges.size(); i++) {
                    if (i > 0) {
                        builder.append(", ");
                    }
                    builder.append(ranges.get(i).asDisplay());
                }
            }
            builder.append('\n');
        });
        if (builder.length() == 0) {
            builder.append("(no file metadata available)\n");
        }
        appendFullFileDiffContext(context, chunk, builder);
        appendRelatedChanges(context, chunk, builder);
        return builder.toString();
    }

    private static void appendFullFileDiffContext(ReviewContext context, ReviewChunk chunk, StringBuilder builder) {
        if (context == null || context.getConfig() == null || context.getFileDiffs() == null) {
            return;
        }
        ReviewMode mode = context.getConfig().getReviewMode();
        if (mode != ReviewMode.DEEP && mode != ReviewMode.FULL) {
            return;
        }

        int perFileLimit = mode == ReviewMode.FULL ? 8000 : 4000;
        int totalLimit = mode == ReviewMode.FULL ? 24000 : 12000;
        int total = 0;
        builder.append("Full diff context for chunk files:\n");
        for (String path : chunk.getFiles()) {
            String diff = context.getFileDiffs().get(path);
            if (diff == null || diff.isBlank()) {
                continue;
            }
            String snippet = diff.length() > perFileLimit
                    ? diff.substring(0, perFileLimit) + "\n...(truncated)"
                    : diff;
            String block = "--- " + path + " ---\n" + snippet + "\n";
            if (total + block.length() > totalLimit) {
                builder.append("- ... (full diff context truncated)\n\n");
                break;
            }
            builder.append(block);
            total += block.length();
        }
        builder.append("\n");
    }

    private static void appendRelatedChanges(ReviewContext context, ReviewChunk chunk, StringBuilder builder) {
        if (context == null || context.getConfig() == null || context.getFileDiffs() == null) {
            return;
        }
        ReviewMode mode = context.getConfig().getReviewMode();
        if (mode != ReviewMode.DEEP && mode != ReviewMode.FULL) {
            return;
        }

        Set<String> tokens = extractIdentifiersFromChunk(chunk.getContent());
        if (tokens.isEmpty()) {
            return;
        }

        int snippetLimit = mode == ReviewMode.FULL ? 20 : 10;
        int snippets = 0;
        builder.append("Related changes in other files:\n");
        for (Map.Entry<String, String> entry : context.getFileDiffs().entrySet()) {
            if (snippets >= snippetLimit) {
                break;
            }
            String path = entry.getKey();
            if (chunk.getFiles().contains(path)) {
                continue;
            }
            String diff = entry.getValue();
            if (diff == null || diff.isBlank()) {
                continue;
            }
            String[] lines = diff.split("\n", -1);
            for (String rawLine : lines) {
                if (snippets >= snippetLimit) {
                    break;
                }
                String line = rawLine.trim();
                if (line.isEmpty() || line.startsWith("diff --git") || line.startsWith("+++") || line.startsWith("---")) {
                    continue;
                }
                if (containsToken(line, tokens)) {
                    builder.append("- ").append(path).append(": ").append(rawLine.strip()).append("\n");
                    snippets++;
                }
            }
        }

        if (snippets == 0) {
            builder.append("- (no related changes detected in other files)\n");
        }
        builder.append("\n");
    }

    private static boolean containsToken(String line, Set<String> tokens) {
        for (String token : tokens) {
            if (line.contains(token)) {
                return true;
            }
        }
        return false;
    }

    private static Set<String> extractIdentifiersFromChunk(String content) {
        if (content == null || content.isBlank()) {
            return Collections.emptySet();
        }
        Set<String> tokens = new LinkedHashSet<>();
        Pattern pattern = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{3,}");
        Set<String> stopWords = new HashSet<>(Arrays.asList(
                "public", "private", "protected", "class", "static", "final",
                "return", "throws", "throw", "new", "void", "int", "long",
                "double", "float", "boolean", "string", "null", "true", "false",
                "this", "super", "extends", "implements", "package", "import",
                "if", "else", "for", "while", "switch", "case", "break",
                "continue", "default", "try", "catch", "finally", "var",
                "const", "let", "function", "async", "await"));

        for (String rawLine : content.split("\n", -1)) {
            String line = rawLine.trim();
            if (!line.startsWith("+") || line.startsWith("+++")) {
                continue;
            }
            Matcher matcher = pattern.matcher(line);
            while (matcher.find()) {
                String token = matcher.group();
                if (!stopWords.contains(token.toLowerCase())) {
                    tokens.add(token);
                }
                if (tokens.size() >= 30) {
                    return tokens;
                }
            }
        }
        return tokens;
    }

    private static void appendGlobalContext(ReviewContext context, StringBuilder builder) {
        if (context == null || context.getConfig() == null || context.getFileStats() == null) {
            return;
        }
        ReviewMode mode = context.getConfig().getReviewMode();
        if (mode != ReviewMode.DEEP && mode != ReviewMode.FULL) {
            return;
        }

        int totalFiles = context.getFileStats().size();
        builder.append("Global change summary (")
                .append(mode.getDisplayName())
                .append("):\n");
        builder.append("- Files changed: ").append(totalFiles).append("\n");

        int limit = mode == ReviewMode.FULL ? 40 : 20;
        int count = 0;
        for (Map.Entry<String, ReviewOverview.FileStats> entry : context.getFileStats().entrySet()) {
            if (count >= limit) {
                builder.append("- ... ").append(totalFiles - count).append(" more files\n");
                break;
            }
            String path = entry.getKey();
            ReviewOverview.FileStats stats = entry.getValue();
            builder.append("- ")
                    .append(path)
                    .append(" (+")
                    .append(stats.getAdditions())
                    .append("/-")
                    .append(stats.getDeletions())
                    .append(")");
            ReviewFileMetadata meta = context.getFileMetadata().get(path);
            if (meta != null) {
                builder.append(" [")
                        .append(meta.getLanguage() != null ? meta.getLanguage() : "unknown");
                if (meta.isTestFile()) {
                    builder.append(", test");
                }
                if (meta.isBinary()) {
                    builder.append(", binary");
                }
                builder.append("]");
            }
            builder.append("\n");
            count++;
        }
        builder.append("\n");
    }

    private static String applyPlaceholders(String template, Map<String, String> replacements) {
        String rendered = Objects.requireNonNull(template, "template");
        for (Map.Entry<String, String> entry : replacements.entrySet()) {
            rendered = rendered.replace(entry.getKey(), entry.getValue());
        }
        return rendered;
    }
}
