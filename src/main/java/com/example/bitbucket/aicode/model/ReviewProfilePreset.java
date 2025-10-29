package com.example.bitbucket.aicode.model;

import javax.annotation.Nonnull;
import java.util.ArrayList;
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
 * Preset review profiles that bundle recommended configuration defaults.
 */
public enum ReviewProfilePreset {

    BALANCED(
            "balanced",
            "Balanced Review",
            "General-purpose review that surfaces medium+ issues and blocks on high severity problems.",
            Settings.builder()
                    .minSeverity("medium")
                    .requireApprovalFor(List.of("critical", "high"))
                    .skipGeneratedFiles(true)
                    .skipTests(false)
                    .maxIssuesPerFile(50)
                    .autoApprove(false)
                    .build()
    ),
    SECURITY_FIRST(
            "security-first",
            "Security Focused",
            "Prioritises security risks by scanning all severities, blocking merge on medium+ findings, and always reviewing tests.",
            Settings.builder()
                    .minSeverity("low")
                    .requireApprovalFor(List.of("critical", "high", "medium"))
                    .skipGeneratedFiles(false)
                    .skipTests(false)
                    .maxIssuesPerFile(75)
                    .autoApprove(false)
                    .build()
    ),
    LIGHTWEIGHT(
            "lightweight",
            "Lightweight Checks",
            "Fast feedback on critical paths only. Flags high/critical issues, skips tests and generated files, and limits comment volume.",
            Settings.builder()
                    .minSeverity("high")
                    .requireApprovalFor(List.of("critical"))
                    .skipGeneratedFiles(true)
                    .skipTests(true)
                    .maxIssuesPerFile(20)
                    .autoApprove(true)
                    .build()
    );

    private final String key;
    private final String displayName;
    private final String description;
    private final Settings settings;

    ReviewProfilePreset(String key,
                        String displayName,
                        String description,
                        Settings settings) {
        this.key = key;
        this.displayName = displayName;
        this.description = description;
        this.settings = settings;
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
    public Settings getSettings() {
        return settings;
    }

    /**
     * Applies preset defaults to a configuration map.
     */
    public void applyTo(@Nonnull Map<String, Object> target) {
        Objects.requireNonNull(target, "target");
        target.put("minSeverity", settings.getMinSeverity());
        target.put("requireApprovalFor", String.join(",", settings.getRequireApprovalFor()));
        target.put("skipGeneratedFiles", settings.isSkipGeneratedFiles());
        target.put("skipTests", settings.isSkipTests());
        target.put("maxIssuesPerFile", settings.getMaxIssuesPerFile());
        target.put("autoApprove", settings.isAutoApprove());
    }

    /**
     * Returns a descriptor map for UI consumption.
     */
    @Nonnull
    public Map<String, Object> toDescriptor() {
        Map<String, Object> descriptor = new LinkedHashMap<>();
        descriptor.put("key", key);
        descriptor.put("name", displayName);
        descriptor.put("description", description);
        descriptor.put("defaults", settings.toMap());
        return Collections.unmodifiableMap(descriptor);
    }

    @Nonnull
    public static Optional<ReviewProfilePreset> fromKey(String key) {
        if (key == null || key.trim().isEmpty()) {
            return Optional.empty();
        }
        String normalised = key.trim().toLowerCase(Locale.ENGLISH);
        for (ReviewProfilePreset preset : values()) {
            if (preset.key.equals(normalised)) {
                return Optional.of(preset);
            }
        }
        return Optional.empty();
    }

    @Nonnull
    public static List<Map<String, Object>> descriptors() {
        return Collections.unmodifiableList(java.util.Arrays.stream(values())
                .map(ReviewProfilePreset::toDescriptor)
                .collect(Collectors.toList()));
    }

    /**
     * Immutable preset defaults.
     */
    public static final class Settings {
        private final String minSeverity;
        private final List<String> requireApprovalFor;
        private final boolean skipGeneratedFiles;
        private final boolean skipTests;
        private final int maxIssuesPerFile;
        private final boolean autoApprove;

        private Settings(Builder builder) {
            this.minSeverity = builder.minSeverity;
            this.requireApprovalFor = Collections.unmodifiableList(new ArrayList<>(builder.requireApprovalFor));
            this.skipGeneratedFiles = builder.skipGeneratedFiles;
            this.skipTests = builder.skipTests;
            this.maxIssuesPerFile = builder.maxIssuesPerFile;
            this.autoApprove = builder.autoApprove;
        }

        @Nonnull
        public String getMinSeverity() {
            return minSeverity;
        }

        @Nonnull
        public List<String> getRequireApprovalFor() {
            return requireApprovalFor;
        }

        public boolean isSkipGeneratedFiles() {
            return skipGeneratedFiles;
        }

        public boolean isSkipTests() {
            return skipTests;
        }

        public int getMaxIssuesPerFile() {
            return maxIssuesPerFile;
        }

        public boolean isAutoApprove() {
            return autoApprove;
        }

        @Nonnull
        private Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("minSeverity", minSeverity);
            map.put("requireApprovalFor", String.join(",", requireApprovalFor));
            map.put("skipGeneratedFiles", skipGeneratedFiles);
            map.put("skipTests", skipTests);
            map.put("maxIssuesPerFile", maxIssuesPerFile);
            map.put("autoApprove", autoApprove);
            return Collections.unmodifiableMap(map);
        }

        public static Builder builder() {
            return new Builder();
        }

        public static final class Builder {
            private String minSeverity = "medium";
            private Set<String> requireApprovalFor = Set.of();
            private boolean skipGeneratedFiles = true;
            private boolean skipTests = false;
            private int maxIssuesPerFile = 50;
            private boolean autoApprove = false;

            private Builder() {
            }

            public Builder minSeverity(@Nonnull String value) {
                this.minSeverity = Objects.requireNonNull(value, "value").toLowerCase(Locale.ENGLISH);
                return this;
            }

            public Builder requireApprovalFor(@Nonnull List<String> values) {
                Objects.requireNonNull(values, "values");
                this.requireApprovalFor = values.stream()
                        .filter(Objects::nonNull)
                        .map(v -> v.toLowerCase(Locale.ENGLISH))
                        .collect(Collectors.toCollection(java.util.LinkedHashSet::new));
                return this;
            }

            public Builder skipGeneratedFiles(boolean value) {
                this.skipGeneratedFiles = value;
                return this;
            }

            public Builder skipTests(boolean value) {
                this.skipTests = value;
                return this;
            }

            public Builder maxIssuesPerFile(int value) {
                this.maxIssuesPerFile = value;
                return this;
            }

            public Builder autoApprove(boolean value) {
                this.autoApprove = value;
                return this;
            }

            public Settings build() {
                return new Settings(this);
            }
        }
    }
}
