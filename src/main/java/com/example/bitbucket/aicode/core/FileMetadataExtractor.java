package com.example.bitbucket.aicode.core;

import com.example.bitbucket.aicode.model.ReviewFileMetadata;
import com.example.bitbucket.aicode.model.ReviewOverview;

import javax.annotation.Nonnull;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Utility that derives per-file metadata used for risk-aware chunk planning.
 */
public final class FileMetadataExtractor {

    private static final Pattern TEST_PATH_PATTERN = Pattern.compile("(^|/)(test|tests|__tests__|spec|specs)(/|$)", Pattern.CASE_INSENSITIVE);

    private FileMetadataExtractor() {
    }

    @Nonnull
    public static Map<String, ReviewFileMetadata> extract(@Nonnull Map<String, ReviewOverview.FileStats> fileStats) {
        if (fileStats.isEmpty()) {
            return Collections.emptyMap();
        }

        Map<String, ReviewFileMetadata> metadata = new LinkedHashMap<>();
        fileStats.forEach((path, stats) -> {
            ReviewFileMetadata.Builder builder = ReviewFileMetadata.builder()
                    .path(path)
                    .binary(stats.isBinary())
                    .additions(stats.getAdditions())
                    .deletions(stats.getDeletions());

            String normalizedPath = path.replace('\\', '/');
            builder.directory(extractDirectory(normalizedPath));
            String extension = extractExtension(normalizedPath);
            builder.extension(extension);
            builder.language(languageForExtension(extension));
            builder.testFile(isTestPath(normalizedPath));

            metadata.put(path, builder.build());
        });
        return metadata;
    }

    private static String extractDirectory(String path) {
        int lastSlash = path.lastIndexOf('/');
        if (lastSlash <= 0) {
            return null;
        }
        return path.substring(0, lastSlash);
    }

    private static String extractExtension(String path) {
        int lastSlash = path.lastIndexOf('/');
        String filename = lastSlash >= 0 ? path.substring(lastSlash + 1) : path;
        int dot = filename.lastIndexOf('.');
        if (dot <= 0 || dot == filename.length() - 1) {
            return null;
        }
        return filename.substring(dot + 1).toLowerCase(Locale.ENGLISH);
    }

    private static boolean isTestPath(String path) {
        return TEST_PATH_PATTERN.matcher(path).find();
    }

    private static String languageForExtension(String extension) {
        if (extension == null) {
            return null;
        }
        switch (extension) {
            case "java":
                return "Java";
            case "kt":
            case "kts":
                return "Kotlin";
            case "groovy":
                return "Groovy";
            case "js":
                return "JavaScript";
            case "ts":
                return "TypeScript";
            case "tsx":
                return "TypeScript React";
            case "jsx":
                return "JavaScript React";
            case "py":
                return "Python";
            case "rb":
                return "Ruby";
            case "go":
                return "Go";
            case "rs":
                return "Rust";
            case "c":
            case "h":
                return "C";
            case "cpp":
            case "cc":
            case "hpp":
                return "C++";
            case "cs":
                return "C#";
            case "php":
                return "PHP";
            case "swift":
                return "Swift";
            case "scala":
                return "Scala";
            case "sql":
                return "SQL";
            case "yaml":
            case "yml":
                return "YAML";
            case "json":
                return "JSON";
            case "xml":
                return "XML";
            case "html":
            case "htm":
                return "HTML";
            default:
                return null;
        }
    }
}
