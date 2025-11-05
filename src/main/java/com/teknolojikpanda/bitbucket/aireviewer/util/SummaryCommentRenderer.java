package com.teknolojikpanda.bitbucket.aireviewer.util;

import com.teknolojikpanda.bitbucket.aireviewer.dto.ReviewIssue;

import javax.annotation.Nonnull;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Renders the summary comment posted by the AI review.
 */
public final class SummaryCommentRenderer {

    private static final String HEADER_TEMPLATE = load("templates/summary/header.md");
    private static final String SEVERITY_ROW_TEMPLATE = load("templates/summary/severity-row.md");
    private static final String FILE_ROW_TEMPLATE = load("templates/summary/file-row.md");
    private static final String ISSUE_BULLET_TEMPLATE = load("templates/summary/issue-bullet.md");
    private static final String CATEGORY_ROW_TEMPLATE = load("templates/summary/category-row.md");
    private static final String RESOLVED_BULLET_TEMPLATE = load("templates/summary/resolved-bullet.md");
    private static final String NEW_BULLET_TEMPLATE = load("templates/summary/new-bullet.md");
    private static final String FOOTER_TEMPLATE = load("templates/summary/footer.md");

    private static final String SEVERITY_TABLE_HEADER = "| Severity | Count |\n|----------|-------|\n";
    private static final String CATEGORY_TABLE_HEADER = "| Category | Count |\n|----------|-------|\n";
    private static final String FILE_TABLE_HEADER = "| File | +Added | -Deleted | Issues |\n|------|--------|----------|--------|\n";

    private SummaryCommentRenderer() {
    }

    @Nonnull
    public static String render(@Nonnull List<ReviewIssue> issues,
                                 @Nonnull Map<String, FileChange> fileChanges,
                                 @Nonnull List<ReviewIssue> resolvedIssues,
                                 @Nonnull List<ReviewIssue> newIssues,
                                 @Nonnull String aiModel,
                                 long elapsedSeconds,
                                 int failedChunks,
                                 @Nonnull java.util.function.Function<ReviewIssue.Severity, String> iconProvider) {
        Objects.requireNonNull(issues, "issues");
        Objects.requireNonNull(fileChanges, "fileChanges");
        Objects.requireNonNull(resolvedIssues, "resolvedIssues");
        Objects.requireNonNull(newIssues, "newIssues");
        Objects.requireNonNull(aiModel, "aiModel");

        SummaryInsights insights = issues.isEmpty() && resolvedIssues.isEmpty() && newIssues.isEmpty()
                ? SummaryInsights.empty()
                : SummaryInsights.analyze(issues, resolvedIssues, newIssues);

        Map<ReviewIssue.Severity, Long> severityCounts = insights.getSeverityCounts();
        long criticalCount = severityCounts.getOrDefault(ReviewIssue.Severity.CRITICAL, 0L);
        long highCount = severityCounts.getOrDefault(ReviewIssue.Severity.HIGH, 0L);
        long mediumCount = severityCounts.getOrDefault(ReviewIssue.Severity.MEDIUM, 0L);
        long lowCount = severityCounts.getOrDefault(ReviewIssue.Severity.LOW, 0L);

        String statusIcon;
        String statusText;
        String statusMessage;
        if (insights.isEmpty()) {
            statusIcon = "✅";
            statusText = "No findings";
            statusMessage = "> ✅ The AI reviewer did not detect any issues in this pull request.";
        } else if (insights.hasBlockingIssues()) {
            long blocking = criticalCount + highCount;
            statusIcon = "🚫";
            statusText = "CHANGES REQUIRED";
            statusMessage = String.format(Locale.ENGLISH,
                    "> ⚠️ This PR has **%d critical/high severity issue(s)** that must be addressed before merging.",
                    blocking);
        } else if (insights.hasOnlyLowOrInfoIssues()) {
            statusIcon = "✅";
            statusText = "Ready after sanity check";
            statusMessage = "> ✅ Only low or informational findings remain. Reviewers may merge after acknowledging these suggestions.";
        } else {
            statusIcon = "⚠️";
            statusText = "Review Recommended";
            statusMessage = "> ℹ️ Medium severity issues were detected. Ensure they are addressed before merge.";
        }

        StringBuilder comment = new StringBuilder();
        comment.append(apply(HEADER_TEMPLATE, Map.of(
                "{{STATUS_ICON}}", statusIcon,
                "{{ISSUE_COUNT}}", String.valueOf(issues.size()),
                "{{STATUS_TEXT}}", statusText,
                "{{STATUS_MESSAGE}}", statusMessage
        ))).append('\n');

        comment.append("### Summary\n").append(SEVERITY_TABLE_HEADER);
        appendSeverityRow(comment, criticalCount, "🔴", "Critical");
        appendSeverityRow(comment, highCount, "🟠", "High");
        appendSeverityRow(comment, mediumCount, "🟡", "Medium");
        appendSeverityRow(comment, lowCount, "🔵", "Low");
        comment.append('\n');

        comment.append(renderGuidance(insights));
        comment.append(renderTopCategories(insights));
        comment.append(renderFileTable(issues, fileChanges));
        comment.append(renderIssuesByFile(issues, iconProvider));
        comment.append(renderReReviewSection(resolvedIssues, newIssues, iconProvider));

        String prStatusLine = insights.hasBlockingIssues()
                ? String.format(Locale.ENGLISH,
                "**🚫 PR Status:** Changes required before merge (%d critical, %d high severity)",
                criticalCount, highCount)
                : "**✅ PR Status:** May merge after review (no blocking issues detected)";

        String warning = failedChunks > 0
                ? String.format(Locale.ENGLISH,
                "⚠️ **Warning**: %d chunk(s) failed to analyze - some issues may be missing.", failedChunks)
                : "";

        comment.append(apply(FOOTER_TEMPLATE, Map.of(
                "{{MODEL}}", aiModel,
                "{{ELAPSED}}", String.valueOf(elapsedSeconds),
                "{{PR_STATUS_LINE}}", prStatusLine,
                "{{FAILED_WARNING}}", warning
        )));
        return comment.toString();
    }

    private static void appendSeverityRow(StringBuilder builder, long count, String icon, String label) {
        if (count <= 0) {
            return;
        }
        builder.append(apply(SEVERITY_ROW_TEMPLATE, Map.of(
                "{{ICON}}", icon,
                "{{LABEL}}", label,
                "{{COUNT}}", String.valueOf(count)
        )));
        builder.append('\n');
    }

    private static String renderGuidance(SummaryInsights insights) {
        if (insights.isEmpty()) {
            return "";
        }

        List<String> guidance = new java.util.ArrayList<>();
        if (insights.hasBlockingIssues()) {
            guidance.add("🚫 Resolve all critical/high severity findings before approving this pull request.");
        } else if (insights.hasOnlyLowOrInfoIssues()) {
            guidance.add("✅ No blocking issues detected. Proceed once low severity suggestions are reviewed.");
        } else {
            guidance.add("⚠️ Medium severity findings remain. Ensure the author addresses them prior to merge.");
        }

        if (insights.getNewIssueCount() > 0) {
            guidance.add(String.format(Locale.ENGLISH,
                    "🆕 %d new issue(s) introduced since the last review run.", insights.getNewIssueCount()));
        }
        if (insights.getResolvedIssueCount() > 0) {
            guidance.add(String.format(Locale.ENGLISH,
                    "✅ %d previously reported issue(s) resolved in this iteration.", insights.getResolvedIssueCount()));
        }
        if (guidance.isEmpty()) {
            return "";
        }

        StringBuilder builder = new StringBuilder("### Review Guidance\n\n");
        for (String message : guidance) {
            builder.append("- ").append(message).append('\n');
        }
        builder.append('\n');
        return builder.toString();
    }

    private static String renderTopCategories(SummaryInsights insights) {
        if (insights.getTopCategories().isEmpty()) {
            return "";
        }

        StringBuilder builder = new StringBuilder("### Top Issue Categories\n\n")
                .append(CATEGORY_TABLE_HEADER);
        for (SummaryInsights.CategorySummary category : insights.getTopCategories()) {
            builder.append(apply(CATEGORY_ROW_TEMPLATE, Map.of(
                    "{{CATEGORY}}", category.getDisplayName(),
                    "{{COUNT}}", String.valueOf(category.getCount())
            )));
            builder.append('\n');
        }
        builder.append('\n');
        return builder.toString();
    }

    private static String renderFileTable(List<ReviewIssue> issues,
                                          Map<String, FileChange> fileChanges) {
        if (fileChanges.isEmpty()) {
            return "### 📁 File-Level Changes\n\n_No file change statistics available_\n\n";
        }

        Map<String, List<ReviewIssue>> issuesByFile = issues.stream()
                .collect(Collectors.groupingBy(ReviewIssue::getPath));

        List<Map.Entry<String, FileChange>> sortedFiles = fileChanges.entrySet().stream()
                .sorted((a, b) -> Integer.compare(
                        b.getValue().getTotalChanges(),
                        a.getValue().getTotalChanges()))
                .collect(Collectors.toList());

        StringBuilder builder = new StringBuilder("### 📁 File-Level Changes\n\n")
                .append(FILE_TABLE_HEADER);

        int filesShown = Math.min(20, sortedFiles.size());
        for (int i = 0; i < filesShown; i++) {
            Map.Entry<String, FileChange> entry = sortedFiles.get(i);
            String fileName = entry.getKey();
            FileChange stats = entry.getValue();
            int issuesInFile = issuesByFile.getOrDefault(fileName, Collections.emptyList()).size();
            String issueIcon = issuesInFile > 0 ? "⚠️" : "✓";
            builder.append(apply(FILE_ROW_TEMPLATE, Map.of(
                    "{{FILE}}", fileName,
                    "{{ADDITIONS}}", String.valueOf(stats.getAdditions()),
                    "{{DELETIONS}}", String.valueOf(stats.getDeletions()),
                    "{{ISSUE_ICON}}", issueIcon,
                    "{{ISSUE_COUNT}}", String.valueOf(issuesInFile)
            )));
            builder.append('\n');
        }

        if (sortedFiles.size() > 20) {
            builder.append("| _...and ").append(sortedFiles.size() - 20).append(" more files_ | | | |\n");
        }

        builder.append('\n');
        int totalAdditions = fileChanges.values().stream().mapToInt(FileChange::getAdditions).sum();
        int totalDeletions = fileChanges.values().stream().mapToInt(FileChange::getDeletions).sum();
        builder.append("**Total Changes:** +").append(totalAdditions)
                .append(" additions, -").append(totalDeletions)
                .append(" deletions across ").append(fileChanges.size()).append(" file(s)\n\n");
        return builder.toString();
    }

    private static String renderIssuesByFile(List<ReviewIssue> issues,
                                             java.util.function.Function<ReviewIssue.Severity, String> iconProvider) {
        Map<String, List<ReviewIssue>> issuesByFile = issues.stream()
                .collect(Collectors.groupingBy(ReviewIssue::getPath));
        if (issuesByFile.isEmpty()) {
            return "";
        }

        StringBuilder builder = new StringBuilder("### Issues by File\n\n");
        int filesWithIssuesShown = 0;
        for (Map.Entry<String, List<ReviewIssue>> entry : issuesByFile.entrySet()) {
            if (filesWithIssuesShown >= 10) {
                break;
            }
            builder.append("#### `").append(entry.getKey()).append("`\n");
            List<ReviewIssue> fileIssues = entry.getValue();
            int issuesShown = Math.min(5, fileIssues.size());
            for (int i = 0; i < issuesShown; i++) {
                ReviewIssue issue = fileIssues.get(i);
                builder.append(apply(ISSUE_BULLET_TEMPLATE, Map.of(
                        "{{ICON}}", iconProvider.apply(issue.getSeverity()),
                        "{{SEVERITY}}", issue.getSeverity().name(),
                        "{{LOCATION}}", buildLocation(issue),
                        "{{CATEGORY}}", issue.getType(),
                        "{{SUMMARY}}", issue.getSummary(),
                        "{{DETAILS}}", renderDetails(issue)
                )));
            }
            if (fileIssues.size() > issuesShown) {
                builder.append("\n_...and ").append(fileIssues.size() - issuesShown)
                        .append(" more issue(s) in this file._\n");
            }
            builder.append('\n');
            filesWithIssuesShown++;
        }
        if (issuesByFile.size() > 10) {
            builder.append("_...and issues in ").append(issuesByFile.size() - 10)
                    .append(" more file(s)._\n\n");
        }
        return builder.toString();
    }

    private static String renderReReviewSection(List<ReviewIssue> resolvedIssues,
                                                List<ReviewIssue> newIssues,
                                                java.util.function.Function<ReviewIssue.Severity, String> iconProvider) {
        if (resolvedIssues.isEmpty() && newIssues.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder("---\n### 🔄 Changes Since Last Review\n\n");
        if (!resolvedIssues.isEmpty()) {
            builder.append("✅ **").append(resolvedIssues.size()).append(" issue(s) resolved:**\n");
            List<ReviewIssue> previews = resolvedIssues.subList(0, Math.min(5, resolvedIssues.size()));
            for (ReviewIssue issue : previews) {
                builder.append(apply(RESOLVED_BULLET_TEMPLATE, Map.of(
                        "{{FILE}}", issue.getPath(),
                        "{{LOCATION}}", buildLocation(issue),
                        "{{SUMMARY}}", summarize(issue.getSummary(), 60)
                )));
            }
            if (resolvedIssues.size() > previews.size()) {
                builder.append("- _...and ").append(resolvedIssues.size() - previews.size()).append(" more_\n");
            }
            builder.append('\n');
        }

        if (!newIssues.isEmpty()) {
            builder.append("🆕 **").append(newIssues.size()).append(" new issue(s) introduced:**\n");
            List<ReviewIssue> previews = newIssues.subList(0, Math.min(5, newIssues.size()));
            for (ReviewIssue issue : previews) {
                builder.append(apply(NEW_BULLET_TEMPLATE, Map.of(
                        "{{ICON}}", iconProvider.apply(issue.getSeverity()),
                        "{{FILE}}", issue.getPath(),
                        "{{LOCATION}}", buildLocation(issue),
                        "{{SUMMARY}}", summarize(issue.getSummary(), 60)
                )));
            }
            if (newIssues.size() > previews.size()) {
                builder.append("- _...and ").append(newIssues.size() - previews.size()).append(" more_\n");
            }
            builder.append('\n');
        }
        return builder.toString();
    }

    private static String renderDetails(ReviewIssue issue) {
        String details = issue.getDetails();
        if (details == null || details.isBlank() || details.length() > 200) {
            return "";
        }
        return "  \n  " + details + "\n";
    }

    private static String buildLocation(ReviewIssue issue) {
        String lineInfo = issue.getLineRangeDisplay();
        if ("?".equals(lineInfo)) {
            return "";
        }
        if (issue.getLineEnd() != null && !Objects.equals(issue.getLineStart(), issue.getLineEnd())) {
            lineInfo += " (multiline)";
        }
        return "L" + lineInfo;
    }

    private static String summarize(String summary, int maxLen) {
        if (summary.length() <= maxLen) {
            return summary;
        }
        return summary.substring(0, maxLen) + "...";
    }

    private static String apply(String template, Map<String, String> replacements) {
        String result = template;
        for (Map.Entry<String, String> entry : replacements.entrySet()) {
            result = result.replace(entry.getKey(), entry.getValue() != null ? entry.getValue() : "");
        }
        return result;
    }

    private static String load(String resourcePath) {
        ClassLoader loader = SummaryCommentRenderer.class.getClassLoader();
        try (InputStream is = loader.getResourceAsStream(resourcePath)) {
            if (is == null) {
                throw new IllegalStateException("Missing template resource: " + resourcePath);
            }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                return reader.lines().collect(Collectors.joining("\n"));
            }
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to load template " + resourcePath + ": " + ex.getMessage(), ex);
        }
    }
}
