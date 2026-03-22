package com.teknolojikpanda.bitbucket.aicode.model;

import javax.annotation.Nonnull;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * Holds prompt template segments for AI interactions.
 */
public final class PromptTemplates {

    public static final String KEY_SYSTEM = "prompt.system";
    public static final String KEY_CHUNK = "prompt.chunk";
    public static final String KEY_OVERVIEW = "prompt.overview";
    public static final String KEY_OVERVIEW_FILE = "prompt.overviewfile";
    public static final String KEY_IMPACT = "prompt.impact";
    public static final String KEY_SYSTEM_APPEND = "prompt.system.append";
    public static final String KEY_CHUNK_APPEND = "prompt.chunk.append";

    private static final Map<String, String> PROMPT_KEY_ALIASES;

    static {
        Map<String, String> aliases = new LinkedHashMap<>();
        aliases.put(KEY_SYSTEM, KEY_SYSTEM);
        aliases.put("system", KEY_SYSTEM);
        aliases.put("systemprompt", KEY_SYSTEM);

        aliases.put(KEY_CHUNK, KEY_CHUNK);
        aliases.put("chunk", KEY_CHUNK);
        aliases.put("chunkinstructions", KEY_CHUNK);

        aliases.put(KEY_OVERVIEW, KEY_OVERVIEW);
        aliases.put("overview", KEY_OVERVIEW);

        aliases.put(KEY_OVERVIEW_FILE, KEY_OVERVIEW_FILE);
        aliases.put("overviewfile", KEY_OVERVIEW_FILE);
        aliases.put("prompt.fileline", KEY_OVERVIEW_FILE);

        aliases.put(KEY_IMPACT, KEY_IMPACT);
        aliases.put("impact", KEY_IMPACT);
        aliases.put("impactsummary", KEY_IMPACT);

        aliases.put(KEY_SYSTEM_APPEND, KEY_SYSTEM_APPEND);
        aliases.put(KEY_CHUNK_APPEND, KEY_CHUNK_APPEND);

        PROMPT_KEY_ALIASES = Collections.unmodifiableMap(aliases);
    }

    private static final String DEFAULT_SYSTEM_PATH = "prompts/system-prompt.txt";
    private static final String DEFAULT_CHUNK_PATH = "prompts/chunk-instructions-template.txt";
    private static final String DEFAULT_OVERVIEW_PATH = "prompts/overview-template.txt";
    private static final String DEFAULT_FILE_ENTRY_PATH = "prompts/overview-file-entry.txt";
    private static final String DEFAULT_IMPACT_PATH = "prompts/impact-summary-template.txt";

    private final String systemPrompt;
    private final String chunkInstructionsTemplate;
    private final String overviewTemplate;
    private final String overviewFileEntryTemplate;
    private final String impactSummaryTemplate;

    private PromptTemplates(Builder builder) {
        this.systemPrompt = builder.systemPrompt;
        this.chunkInstructionsTemplate = builder.chunkInstructionsTemplate;
        this.overviewTemplate = builder.overviewTemplate;
        this.overviewFileEntryTemplate = builder.overviewFileEntryTemplate;
    this.impactSummaryTemplate = builder.impactSummaryTemplate;
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
    public String getImpactSummaryTemplate() {
        return impactSummaryTemplate;
    }

    @Nonnull
    public PromptTemplates withOverrides(@Nonnull Map<String, String> overrides) {
        Builder builder = new Builder()
                .systemPrompt(systemPrompt)
                .chunkInstructionsTemplate(chunkInstructionsTemplate)
                .overviewTemplate(overviewTemplate)
                .overviewFileEntryTemplate(overviewFileEntryTemplate)
                .impactSummaryTemplate(impactSummaryTemplate);
        overrides.forEach((key, value) -> {
            if (value == null) {
                return;
            }
            String canonical = canonicalPromptKey(key);
            if (canonical == null) {
                return;
            }
            switch (canonical) {
                case KEY_SYSTEM:
                    builder.systemPrompt(value);
                    break;
                case KEY_CHUNK:
                    builder.chunkInstructionsTemplate(value);
                    break;
                case KEY_OVERVIEW:
                    builder.overviewTemplate(value);
                    break;
                case KEY_OVERVIEW_FILE:
                    builder.overviewFileEntryTemplate(value);
                    break;
                case KEY_IMPACT:
                    builder.impactSummaryTemplate(value);
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
                .impactSummaryTemplate(readResource(DEFAULT_IMPACT_PATH))
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

    public static Set<String> supportedPromptKeys() {
        return PROMPT_KEY_ALIASES.keySet();
    }

    public static String canonicalPromptKey(String key) {
        if (key == null) {
            return null;
        }
        String normalized = key.trim().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty()) {
            return null;
        }
        return PROMPT_KEY_ALIASES.get(normalized);
    }

    public static final class Builder {
        private String systemPrompt;
        private String chunkInstructionsTemplate;
        private String overviewTemplate;
        private String overviewFileEntryTemplate;
    private String impactSummaryTemplate;

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

        public Builder impactSummaryTemplate(@Nonnull String value) {
            this.impactSummaryTemplate = Objects.requireNonNull(value, "value");
            return this;
        }

        public PromptTemplates build() {
            Objects.requireNonNull(systemPrompt, "systemPrompt");
            Objects.requireNonNull(chunkInstructionsTemplate, "chunkInstructionsTemplate");
            Objects.requireNonNull(overviewTemplate, "overviewTemplate");
            Objects.requireNonNull(overviewFileEntryTemplate, "overviewFileEntryTemplate");
            Objects.requireNonNull(impactSummaryTemplate, "impactSummaryTemplate");
            return new PromptTemplates(this);
        }
    }
}
