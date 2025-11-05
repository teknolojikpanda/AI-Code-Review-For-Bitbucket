package com.teknolojikpanda.bitbucket.aicode.core;

import com.teknolojikpanda.bitbucket.aicode.model.PromptTemplates;
import com.teknolojikpanda.bitbucket.aicode.model.ReviewConfig;
import com.teknolojikpanda.bitbucket.aicode.model.ReviewContext;
import com.teknolojikpanda.bitbucket.aicode.model.ReviewOverview;
import com.teknolojikpanda.bitbucket.aicode.model.ReviewPreparation;
import com.teknolojikpanda.bitbucket.aicode.model.ReviewProfile;
import com.teknolojikpanda.bitbucket.aicode.model.ReviewChunk;
import com.teknolojikpanda.bitbucket.aicode.model.ReviewFileMetadata;

import javax.annotation.Nonnull;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

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

    static String renderChunkInstructions(@Nonnull PromptTemplates templates,
                                          @Nonnull ReviewConfig config,
                                          @Nonnull ReviewContext context,
                                          @Nonnull ReviewChunk chunk,
                                          @Nonnull String overview,
                                          @Nonnull String annotatedDiff) {
        Map<String, String> placeholders = new LinkedHashMap<>();
        placeholders.put("{{OVERVIEW}}", overview);
        ReviewProfile profile = config.getProfile();
        placeholders.put("{{MIN_SEVERITY}}", profile.getMinSeverity().name().toLowerCase());
        placeholders.put("{{CHUNK_CONTEXT}}", buildChunkContext(context, chunk));
        placeholders.put("{{ANNOTATED_DIFF}}", annotatedDiff);
        return applyPlaceholders(templates.getChunkInstructionsTemplate(), placeholders);
    }

    private static String buildChunkContext(ReviewContext context, ReviewChunk chunk) {
        StringBuilder builder = new StringBuilder();
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
            if (chunk.getPrimaryRanges().containsKey(path)) {
                builder.append(" lines ")
                        .append(chunk.getPrimaryRanges().get(path).asDisplay());
            }
            builder.append('\n');
        });
        if (builder.length() == 0) {
            builder.append("(no file metadata available)\n");
        }
        return builder.toString();
    }

    private static String applyPlaceholders(String template, Map<String, String> replacements) {
        String rendered = Objects.requireNonNull(template, "template");
        for (Map.Entry<String, String> entry : replacements.entrySet()) {
            rendered = rendered.replace(entry.getKey(), entry.getValue());
        }
        return rendered;
    }
}
