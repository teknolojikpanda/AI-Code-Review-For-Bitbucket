package com.teknolojikpanda.bitbucket.aicode.model;

import javax.annotation.Nonnull;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Holds prompt template segments for AI interactions.
 */
public final class PromptTemplates {

    private static final String DEFAULT_SYSTEM_PATH = "prompts/system-prompt.txt";
    private static final String DEFAULT_CHUNK_PATH = "prompts/chunk-instructions-template.txt";
    private static final String DEFAULT_OVERVIEW_PATH = "prompts/overview-template.txt";
    private static final String DEFAULT_FILE_ENTRY_PATH = "prompts/overview-file-entry.txt";

    private final String systemPrompt;
    private final String chunkInstructionsTemplate;
    private final String overviewTemplate;
    private final String overviewFileEntryTemplate;

    private PromptTemplates(Builder builder) {
        this.systemPrompt = builder.systemPrompt;
        this.chunkInstructionsTemplate = builder.chunkInstructionsTemplate;
        this.overviewTemplate = builder.overviewTemplate;
        this.overviewFileEntryTemplate = builder.overviewFileEntryTemplate;
    }

    @Nonnull
    public String getSystemPrompt() {
        return systemPrompt;
    }

    @Nonnull
    public String getChunkInstructionsTemplate() {
        return chunkInstructionsTemplate;
    }

    @Nonnull
    public String getOverviewTemplate() {
        return overviewTemplate;
    }

    @Nonnull
    public String getOverviewFileEntryTemplate() {
        return overviewFileEntryTemplate;
    }

    @Nonnull
    public PromptTemplates withOverrides(@Nonnull Map<String, String> overrides) {
        Builder builder = new Builder()
                .systemPrompt(systemPrompt)
                .chunkInstructionsTemplate(chunkInstructionsTemplate)
                .overviewTemplate(overviewTemplate)
                .overviewFileEntryTemplate(overviewFileEntryTemplate);
        overrides.forEach((key, value) -> {
            if (value == null) {
                return;
            }
            switch (key.toLowerCase()) {
                case "system":
                case "systemprompt":
                case "prompt.system":
                    builder.systemPrompt(value);
                    break;
                case "chunk":
                case "chunkinstructions":
                case "prompt.chunk":
                    builder.chunkInstructionsTemplate(value);
                    break;
                case "overview":
                case "prompt.overview":
                    builder.overviewTemplate(value);
                    break;
                case "overviewfile":
                case "prompt.fileline":
                case "prompt.overviewfile":
                    builder.overviewFileEntryTemplate(value);
                    break;
                default:
                    // ignore unknown keys
            }
        });
        return builder.build();
    }

    @Nonnull
    public static PromptTemplates loadDefaults() {
        return new Builder()
                .systemPrompt(readResource(DEFAULT_SYSTEM_PATH))
                .chunkInstructionsTemplate(readResource(DEFAULT_CHUNK_PATH))
                .overviewTemplate(readResource(DEFAULT_OVERVIEW_PATH))
                .overviewFileEntryTemplate(readResource(DEFAULT_FILE_ENTRY_PATH))
                .build();
    }

    private static String readResource(String path) {
        ClassLoader loader = PromptTemplates.class.getClassLoader();
        try (InputStream is = loader.getResourceAsStream(path)) {
            if (is == null) {
                throw new IllegalStateException("Missing prompt template resource: " + path);
            }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                return reader.lines().collect(Collectors.joining("\n"));
            }
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to load prompt template " + path + ": " + ex.getMessage(), ex);
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String systemPrompt;
        private String chunkInstructionsTemplate;
        private String overviewTemplate;
        private String overviewFileEntryTemplate;

        public Builder systemPrompt(@Nonnull String value) {
            this.systemPrompt = Objects.requireNonNull(value, "value");
            return this;
        }

        public Builder chunkInstructionsTemplate(@Nonnull String value) {
            this.chunkInstructionsTemplate = Objects.requireNonNull(value, "value");
            return this;
        }

        public Builder overviewTemplate(@Nonnull String value) {
            this.overviewTemplate = Objects.requireNonNull(value, "value");
            return this;
        }

        public Builder overviewFileEntryTemplate(@Nonnull String value) {
            this.overviewFileEntryTemplate = Objects.requireNonNull(value, "value");
            return this;
        }

        public PromptTemplates build() {
            Objects.requireNonNull(systemPrompt, "systemPrompt");
            Objects.requireNonNull(chunkInstructionsTemplate, "chunkInstructionsTemplate");
            Objects.requireNonNull(overviewTemplate, "overviewTemplate");
            Objects.requireNonNull(overviewFileEntryTemplate, "overviewFileEntryTemplate");
            return new PromptTemplates(this);
        }
    }
}
