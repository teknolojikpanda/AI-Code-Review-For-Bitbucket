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
import java.util.Comparator;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

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
        placeholders.put("{{AST_CONTEXT}}", buildAstContext(context, chunk));
        placeholders.put("{{RAG_EVIDENCE}}", buildRagEvidence(context, chunk));
        placeholders.put("{{REASONING_GUIDE}}", buildReasoningGuide());
        placeholders.put("{{ANNOTATED_DIFF}}", annotatedDiff);
        return applyPlaceholders(templates.getChunkInstructionsTemplate(), placeholders);
    }

    private static String buildAstContext(ReviewContext context, ReviewChunk chunk) {
        if (context == null || chunk == null) {
            return "- (ast context unavailable)";
        }

        Set<String> symbols = new LinkedHashSet<>();
        List<String> relationships = new java.util.ArrayList<>();

        Pattern classPattern = Pattern.compile("\\b(class|interface|enum|record)\\s+([A-Za-z_][A-Za-z0-9_]*)");
        Pattern methodPattern = Pattern.compile("\\b([A-Za-z_][A-Za-z0-9_<>,\\[\\]]*)\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*\\(");
        Pattern callPattern = Pattern.compile("\\b([A-Za-z_][A-Za-z0-9_]*)\\s*\\(");

        for (String rawLine : chunk.getContent().split("\\n", -1)) {
            String line = rawLine.trim();
            if (line.isEmpty() || (!line.startsWith("+") && !line.startsWith("-"))) {
                continue;
            }

            Matcher classMatcher = classPattern.matcher(line);
            if (classMatcher.find()) {
                symbols.add(classMatcher.group(2));
                relationships.add("- type " + classMatcher.group(2) + " declared as " + classMatcher.group(1));
            }

            Matcher methodMatcher = methodPattern.matcher(line);
            if (methodMatcher.find()) {
                String methodName = methodMatcher.group(2);
                symbols.add(methodName);
                relationships.add("- method/function declaration: " + methodName);
            }

            Matcher callMatcher = callPattern.matcher(line);
            int callCount = 0;
            while (callMatcher.find() && callCount < 2) {
                String callee = callMatcher.group(1);
                String lower = callee.toLowerCase();
                if (!lower.equals("if") && !lower.equals("for") && !lower.equals("while") && !lower.equals("switch")) {
                    symbols.add(callee);
                    relationships.add("- call site references: " + callee + "(...)" );
                    callCount++;
                }
            }
        }

        StringBuilder builder = new StringBuilder();
        if (symbols.isEmpty() && relationships.isEmpty()) {
            return "- (no strong AST-like signals detected in chunk)";
        }
        if (!symbols.isEmpty()) {
            builder.append("- key symbols: ")
                    .append(String.join(", ", symbols.stream().limit(12).collect(Collectors.toList())))
                    .append("\n");
        }
        relationships.stream().limit(8).forEach(item -> builder.append(item).append("\n"));

        Set<String> touched = new LinkedHashSet<>(chunk.getFiles());
        if (!touched.isEmpty()) {
            builder.append("- touched files for AST scope: ")
                    .append(String.join(", ", touched.stream().limit(6).collect(Collectors.toList())))
                    .append("\n");
        }
        return builder.toString().trim();
    }

    private static String buildRagEvidence(ReviewContext context, ReviewChunk chunk) {
        if (context == null || context.getFileDiffs() == null) {
            return "- (rag evidence unavailable)";
        }

        Set<String> queryTokens = extractIdentifiersFromChunk(chunk.getContent());
        if (queryTokens.isEmpty()) {
            return "- (insufficient identifiers for retrieval evidence)";
        }

        List<EvidenceSnippet> snippets = new java.util.ArrayList<>();
        for (Map.Entry<String, String> entry : context.getFileDiffs().entrySet()) {
            String path = entry.getKey();
            String diff = entry.getValue();
            if (diff == null || diff.isBlank()) {
                continue;
            }
            String[] lines = diff.split("\\n", -1);
            for (String line : lines) {
                String normalized = line.trim();
                if (normalized.isEmpty()) {
                    continue;
                }
                int score = scoreLineAgainstTokens(normalized, queryTokens);
                if (score > 0) {
                    snippets.add(new EvidenceSnippet(path, normalized, score));
                }
            }
        }

        if (snippets.isEmpty()) {
            return "- (no related evidence found in repository diff context)";
        }

        snippets.sort(Comparator.comparingInt(EvidenceSnippet::getScore).reversed());

        StringBuilder builder = new StringBuilder();
        int limit = 8;
        for (int i = 0; i < snippets.size() && i < limit; i++) {
            EvidenceSnippet snippet = snippets.get(i);
            builder.append("- [")
                    .append(snippet.getPath())
                    .append("] score=")
                    .append(snippet.getScore())
                    .append(" :: ")
                    .append(truncate(snippet.getLine(), 220))
                    .append("\n");
        }
        return builder.toString().trim();
    }

    private static int scoreLineAgainstTokens(String line, Set<String> tokens) {
        int score = 0;
        for (String token : tokens) {
            if (line.contains(token)) {
                score += Math.min(token.length(), 12);
            }
        }
        return score;
    }

    private static String truncate(String value, int maxLen) {
        if (value == null || value.length() <= maxLen) {
            return value;
        }
        return value.substring(0, maxLen) + " ...";
    }

    private static String buildReasoningGuide() {
        return String.join("\n",
                "1. Start from concrete evidence lines before making claims.",
                "2. Map each finding to the exact [Line N] marker in added code.",
                "3. Correlate with AST symbols (types/methods/calls) to avoid shallow pattern matching.",
                "4. Prefer high-confidence defects with clear runtime/security impact.",
                "5. If evidence is weak or ambiguous, do not report the issue.");
    }

    private static final class EvidenceSnippet {
        private final String path;
        private final String line;
        private final int score;

        private EvidenceSnippet(String path, String line, int score) {
            this.path = path;
            this.line = line;
            this.score = score;
        }

        private String getPath() {
            return path;
        }

        private String getLine() {
            return line;
        }

        private int getScore() {
            return score;
        }
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
