package com.teknolojikpanda.bitbucket.aicode.model;

import javax.annotation.Nonnull;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Review scope presets that bundle chunking and profile recommendations.
 */
public enum ReviewMode {

    QUICK(
            "quick",
            "Quick",
            "Fast feedback focusing on high-severity issues in the diff.",
            ModeDefaults.builder()
                    .maxCharsPerChunk(40_000)
                    .maxFilesPerChunk(2)
                    .maxChunks(10)
                    .profilePreset(ReviewProfilePreset.LIGHTWEIGHT)
                    .promptInstructions("Quick scan. Focus on high-impact defects, avoid low-severity nits, and keep output concise.")
                    .build()
    ),
    STANDARD(
            "standard",
            "Standard",
            "Balanced review using the configured thresholds.",
            ModeDefaults.builder()
                    .promptInstructions("Balanced review. Consider diff context and flag actionable issues at or above the configured severity.")
                    .build()
    ),
    DEEP(
            "deep",
            "Deep",
            "Thorough review with broader coverage and test awareness.",
            ModeDefaults.builder()
                    .maxCharsPerChunk(80_000)
                    .maxFilesPerChunk(5)
                    .maxChunks(30)
                    .profilePreset(ReviewProfilePreset.SECURITY_FIRST)
                    .promptInstructions("Thorough review. Consider cross-file impact, test coverage gaps, and regression risks.")
                    .build()
    ),
    FULL(
            "full",
            "Full Impact",
            "Maximum coverage including cross-file impact analysis.",
            ModeDefaults.builder()
                    .maxCharsPerChunk(100_000)
                    .maxFilesPerChunk(6)
                    .maxChunks(40)
                    .maxIssuesPerFile(100)
                    .profilePreset(ReviewProfilePreset.SECURITY_FIRST)
                    .promptInstructions("Full impact analysis. Consider repo-wide ripple effects, API compatibility, and missing updates.")
                    .build()
    );

    private final String key;
    private final String displayName;
    private final String description;
    private final ModeDefaults defaults;

    ReviewMode(String key,
               String displayName,
               String description,
               ModeDefaults defaults) {
        this.key = key;
        this.displayName = displayName;
        this.description = description;
        this.defaults = defaults;
    }

    @Nonnull
    public String getKey() {
        return key;
    }

    @Nonnull
    public String getDisplayName() {
        return displayName;
    }

    @Nonnull
    public String getDescription() {
        return description;
    }

    @Nonnull
    public String toConfigValue() {
        return key;
    }

    @Nonnull
    public String getPromptInstructions() {
        return defaults != null ? defaults.promptInstructions : "";
    }

    public void applyTo(@Nonnull ReviewConfig.Builder configBuilder,
                        @Nonnull ReviewProfile.Builder profileBuilder) {
        Objects.requireNonNull(configBuilder, "configBuilder");
        Objects.requireNonNull(profileBuilder, "profileBuilder");
        if (defaults != null) {
            defaults.apply(configBuilder, profileBuilder);
        }
    }

    @Nonnull
    public Map<String, Object> toDescriptor() {
        Map<String, Object> descriptor = new LinkedHashMap<>();
        descriptor.put("key", key);
        descriptor.put("name", displayName);
        descriptor.put("description", description);
        descriptor.put("defaults", defaults != null ? defaults.toMap() : Collections.emptyMap());
        return Collections.unmodifiableMap(descriptor);
    }

    @Nonnull
    public static Optional<ReviewMode> fromConfigValue(String value) {
        if (value == null || value.trim().isEmpty()) {
            return Optional.empty();
        }
        String normalized = value.trim().toLowerCase(Locale.ENGLISH);
        for (ReviewMode mode : values()) {
            if (mode.key.equals(normalized)) {
                return Optional.of(mode);
            }
        }
        return Optional.empty();
    }

    @Nonnull
    public static ReviewMode fromConfigValueOrDefault(String value) {
        return fromConfigValue(value).orElse(STANDARD);
    }

    @Nonnull
    public static List<Map<String, Object>> descriptors() {
        return java.util.Arrays.stream(values())
                .map(ReviewMode::toDescriptor)
                .collect(Collectors.toUnmodifiableList());
    }

    private static final class ModeDefaults {
        private final Integer maxCharsPerChunk;
        private final Integer maxFilesPerChunk;
        private final Integer maxChunks;
        private final Integer maxIssuesPerFile;
        private final ReviewProfilePreset profilePreset;
        private final String promptInstructions;

        private ModeDefaults(Builder builder) {
            this.maxCharsPerChunk = builder.maxCharsPerChunk;
            this.maxFilesPerChunk = builder.maxFilesPerChunk;
            this.maxChunks = builder.maxChunks;
            this.maxIssuesPerFile = builder.maxIssuesPerFile;
            this.profilePreset = builder.profilePreset;
            this.promptInstructions = builder.promptInstructions != null ? builder.promptInstructions : "";
        }

        private void apply(ReviewConfig.Builder configBuilder, ReviewProfile.Builder profileBuilder) {
            if (maxCharsPerChunk != null) {
                configBuilder.maxCharsPerChunk(maxCharsPerChunk);
            }
            if (maxFilesPerChunk != null) {
                configBuilder.maxFilesPerChunk(maxFilesPerChunk);
            }
            if (maxChunks != null) {
                configBuilder.maxChunks(maxChunks);
            }
            if (profilePreset != null) {
                applyPreset(profilePreset, profileBuilder);
            }
            if (maxIssuesPerFile != null) {
                profileBuilder.maxIssuesPerFile(maxIssuesPerFile);
            }
        }

        private void applyPreset(ReviewProfilePreset preset, ReviewProfile.Builder profileBuilder) {
            ReviewProfilePreset.Settings settings = preset.getSettings();
            profileBuilder.minSeverity(SeverityLevel.fromString(settings.getMinSeverity()));
            Set<SeverityLevel> approvals = settings.getRequireApprovalFor().stream()
                    .map(SeverityLevel::fromString)
                    .collect(Collectors.toSet());
            profileBuilder.requireApprovalFor(approvals);
            profileBuilder.skipGeneratedFiles(settings.isSkipGeneratedFiles());
            profileBuilder.reviewTests(!settings.isSkipTests());
            profileBuilder.maxIssuesPerFile(settings.getMaxIssuesPerFile());
        }

        private Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            if (maxCharsPerChunk != null) {
                map.put("maxCharsPerChunk", maxCharsPerChunk);
            }
            if (maxFilesPerChunk != null) {
                map.put("maxFilesPerChunk", maxFilesPerChunk);
            }
            if (maxChunks != null) {
                map.put("maxChunks", maxChunks);
            }
            if (maxIssuesPerFile != null) {
                map.put("maxIssuesPerFile", maxIssuesPerFile);
            }
            if (profilePreset != null) {
                map.put("profilePreset", profilePreset.getKey());
            }
            if (promptInstructions != null && !promptInstructions.isEmpty()) {
                map.put("promptInstructions", promptInstructions);
            }
            return Collections.unmodifiableMap(map);
        }

        private static Builder builder() {
            return new Builder();
        }

        private static final class Builder {
            private Integer maxCharsPerChunk;
            private Integer maxFilesPerChunk;
            private Integer maxChunks;
            private Integer maxIssuesPerFile;
            private ReviewProfilePreset profilePreset;
            private String promptInstructions;

            private Builder maxCharsPerChunk(int value) {
                this.maxCharsPerChunk = value;
                return this;
            }

            private Builder maxFilesPerChunk(int value) {
                this.maxFilesPerChunk = value;
                return this;
            }

            private Builder maxChunks(int value) {
                this.maxChunks = value;
                return this;
            }

            private Builder maxIssuesPerFile(int value) {
                this.maxIssuesPerFile = value;
                return this;
            }

            private Builder profilePreset(ReviewProfilePreset preset) {
                this.profilePreset = preset;
                return this;
            }

            private Builder promptInstructions(String value) {
                this.promptInstructions = value;
                return this;
            }

            private ModeDefaults build() {
                return new ModeDefaults(this);
            }
        }
    }
}
