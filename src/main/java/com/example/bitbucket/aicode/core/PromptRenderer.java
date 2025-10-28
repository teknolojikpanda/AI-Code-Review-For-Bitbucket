package com.example.bitbucket.aicode.core;

import com.example.bitbucket.aicode.model.PromptTemplates;
import com.example.bitbucket.aicode.model.ReviewConfig;
import com.example.bitbucket.aicode.model.ReviewOverview;
import com.example.bitbucket.aicode.model.ReviewPreparation;
import com.example.bitbucket.aicode.model.ReviewProfile;

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
                                          @Nonnull String overview,
                                          @Nonnull String annotatedDiff) {
        Map<String, String> placeholders = new LinkedHashMap<>();
        placeholders.put("{{OVERVIEW}}", overview);
        ReviewProfile profile = config.getProfile();
        placeholders.put("{{MIN_SEVERITY}}", profile.getMinSeverity().name().toLowerCase());
        placeholders.put("{{ANNOTATED_DIFF}}", annotatedDiff);
        return applyPlaceholders(templates.getChunkInstructionsTemplate(), placeholders);
    }

    private static String applyPlaceholders(String template, Map<String, String> replacements) {
        String rendered = Objects.requireNonNull(template, "template");
        for (Map.Entry<String, String> entry : replacements.entrySet()) {
            rendered = rendered.replace(entry.getKey(), entry.getValue());
        }
        return rendered;
    }
}
