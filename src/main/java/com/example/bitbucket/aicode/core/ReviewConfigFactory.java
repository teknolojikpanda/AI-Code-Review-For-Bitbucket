package com.example.bitbucket.aicode.core;

import com.atlassian.plugin.spring.scanner.annotation.export.ExportAsService;
import com.example.bitbucket.aicode.model.ReviewConfig;
import com.example.bitbucket.aicode.model.ReviewProfile;
import com.example.bitbucket.aicode.model.SeverityLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import javax.inject.Named;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Translates configuration map from Active Objects/service into typed ReviewConfig.
 */
@Named
@ExportAsService(ReviewConfigFactory.class)
public class ReviewConfigFactory {

    private static final Logger log = LoggerFactory.getLogger(ReviewConfigFactory.class);

    @Nonnull
    public ReviewConfig from(@Nonnull Map<String, Object> config) {
        ReviewConfig.Builder builder = ReviewConfig.builder();

        builder.primaryModelEndpoint(toUri(config.getOrDefault("ollamaUrl", "http://0.0.0.0:11434")));
        builder.primaryModel(stringValue(config.get("ollamaModel"), "qwen3-coder:30b"));
        builder.fallbackModelEndpoint(toUri(config.getOrDefault("ollamaUrl", "http://0.0.0.0:11434")));
        builder.fallbackModel(stringValue(config.get("fallbackModel"), "qwen3-coder:7b"));

        builder.maxCharsPerChunk(intValue(config.get("maxCharsPerChunk"), 60_000));
        builder.maxFilesPerChunk(intValue(config.get("maxFilesPerChunk"), 3));
        builder.maxChunks(intValue(config.get("maxChunks"), 20));
        builder.parallelThreads(intValue(config.get("parallelThreads"), 4));
        builder.requestTimeoutMs(intValue(config.get("ollamaTimeout"), 300_000));
        builder.connectTimeoutMs(intValue(config.get("connectTimeout"), 10_000));
        builder.maxRetries(intValue(config.get("maxRetries"), 3));
        builder.baseRetryDelayMs(intValue(config.get("baseRetryDelay"), 1_000));
        builder.maxDiffBytes(intValue(config.get("maxDiffSize"), 10_000_000));

        builder.reviewableExtensions(splitToSet(config.get("reviewExtensions")));
        builder.ignorePatterns(splitToList(config.get("ignorePatterns")));
        builder.ignorePaths(splitToList(config.get("ignorePaths")));

        builder.profile(buildProfile(config));

        return builder.build();
    }

    private ReviewProfile buildProfile(Map<String, Object> config) {
        ReviewProfile.Builder builder = ReviewProfile.builder();

        String minSeverity = stringValue(config.get("minSeverity"), "medium");
        builder.minSeverity(SeverityLevel.fromString(minSeverity));

        Set<SeverityLevel> approval = splitToSet(config.get("requireApprovalFor")).stream()
                .map(SeverityLevel::fromString)
                .collect(Collectors.toSet());
        builder.requireApprovalFor(approval);

        builder.maxIssuesPerFile(intValue(config.get("maxIssuesPerFile"), 50));
        builder.skipGeneratedFiles(booleanValue(config.get("skipGeneratedFiles"), true));
        builder.reviewTests(!booleanValue(config.get("skipTests"), false));
        return builder.build();
    }

    private URI toUri(Object value) {
        try {
            return new URI(stringValue(value, "http://0.0.0.0:11434"));
        } catch (URISyntaxException e) {
            log.warn("Invalid URI '{}', falling back to default", value);
            try {
                return new URI("http://0.0.0.0:11434");
            } catch (URISyntaxException ex) {
                throw new IllegalStateException(ex);
            }
        }
    }

    private String stringValue(Object value, String defaultValue) {
        return value instanceof String && !((String) value).isEmpty()
                ? (String) value
                : defaultValue;
    }

    private int intValue(Object value, int defaultValue) {
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        if (value instanceof String) {
            try {
                return Integer.parseInt((String) value);
            } catch (NumberFormatException ignored) {
            }
        }
        return defaultValue;
    }

    private boolean booleanValue(Object value, boolean defaultValue) {
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        if (value instanceof String) {
            return Boolean.parseBoolean((String) value);
        }
        return defaultValue;
    }

    private Set<String> splitToSet(Object value) {
        List<String> list = splitToList(value);
        return list.isEmpty() ? Collections.emptySet() : new HashSet<>(list);
    }

    private List<String> splitToList(Object value) {
        if (value instanceof String && !((String) value).trim().isEmpty()) {
            return Arrays.stream(((String) value).split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .collect(Collectors.toList());
        }
        return Collections.emptyList();
    }
}
