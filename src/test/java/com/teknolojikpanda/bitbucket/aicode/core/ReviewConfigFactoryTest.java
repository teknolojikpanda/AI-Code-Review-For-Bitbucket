package com.teknolojikpanda.bitbucket.aicode.core;

import com.teknolojikpanda.bitbucket.aicode.model.ReviewConfig;
import com.teknolojikpanda.bitbucket.aicode.model.ReviewMode;
import com.teknolojikpanda.bitbucket.aicode.model.SeverityLevel;
import org.junit.Before;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ReviewConfigFactoryTest {

    private ReviewConfigFactory factory;

    @Before
    public void setUp() {
        factory = new ReviewConfigFactory();
    }

    @Test
    public void respectsChunkAndOverviewOverrides() {
        Map<String, Object> config = new HashMap<>();
        config.put("chunkMaxRetries", 5);
        config.put("chunkRetryDelay", 1800);
        config.put("overviewMaxRetries", 2);
        config.put("overviewRetryDelay", 3200);

        ReviewConfig reviewConfig = factory.from(config);

        assertEquals(5, reviewConfig.getChunkMaxRetries());
        assertEquals(1800, reviewConfig.getChunkRetryDelayMs());
        assertEquals(2, reviewConfig.getOverviewMaxRetries());
        assertEquals(3200, reviewConfig.getOverviewRetryDelayMs());
    }

    @Test
    public void fallsBackToLegacyRetryKeys() {
        Map<String, Object> config = new HashMap<>();
        config.put("maxRetries", 4);
        config.put("baseRetryDelay", 2500);

        ReviewConfig reviewConfig = factory.from(config);

        assertEquals(4, reviewConfig.getChunkMaxRetries());
        assertEquals(4, reviewConfig.getOverviewMaxRetries());
        assertEquals(2500, reviewConfig.getChunkRetryDelayMs());
        assertEquals(2500, reviewConfig.getOverviewRetryDelayMs());
    }

    @Test
    public void appliesPromptOverridesAndAppends() {
        Map<String, Object> config = new HashMap<>();
        config.put("prompt.chunk", "USER_PROMPT");
        config.put("prompt.chunk.append", "REPO_APPEND");
        config.put("prompt.system", "SYSTEM_PROMPT");
        config.put("prompt.system.append", "SYSTEM_APPEND");

        ReviewConfig reviewConfig = factory.from(config);

        assertEquals("USER_PROMPT\nADDITIONAL INSTRUCTIONS:\nREPO_APPEND",
                reviewConfig.getPromptTemplates().getChunkInstructionsTemplate());
        assertEquals("SYSTEM_PROMPT\nADDITIONAL INSTRUCTIONS:\nSYSTEM_APPEND",
                reviewConfig.getPromptTemplates().getSystemPrompt());
    }

    @Test
    public void whitespaceOnlyAppendsAreIgnoredAndPlaceholderRemoved() {
        Map<String, Object> config = new HashMap<>();
        config.put("prompt.chunk", "CHUNK BASE {{ADDITIONAL_INSTRUCTIONS}}");
        config.put("prompt.chunk.append", "   \t  ");
        config.put("prompt.system", "SYSTEM BASE {{ADDITIONAL_INSTRUCTIONS}}");
        config.put("prompt.system.append", "\n  ");

        ReviewConfig reviewConfig = factory.from(config);

        assertEquals("CHUNK BASE", reviewConfig.getPromptTemplates().getChunkInstructionsTemplate());
        assertEquals("SYSTEM BASE", reviewConfig.getPromptTemplates().getSystemPrompt());
    }

    @Test
    public void placeholderRemovedWhenNoAppendProvided() {
        Map<String, Object> config = new HashMap<>();
        config.put("prompt.chunk", "CHUNK NO APPEND {{ADDITIONAL_INSTRUCTIONS}}");
        config.put("prompt.system", "SYSTEM NO APPEND {{ADDITIONAL_INSTRUCTIONS}}");

        ReviewConfig reviewConfig = factory.from(config);

        assertEquals("CHUNK NO APPEND", reviewConfig.getPromptTemplates().getChunkInstructionsTemplate());
        assertEquals("SYSTEM NO APPEND", reviewConfig.getPromptTemplates().getSystemPrompt());
    }

    @Test
    public void appendsAddedWhenPlaceholderMissing() {
        Map<String, Object> config = new HashMap<>();
        config.put("prompt.chunk", "CHUNK WITHOUT PLACEHOLDER");
        config.put("prompt.chunk.append", "CHUNK APPEND");
        config.put("prompt.system", "SYSTEM WITHOUT PLACEHOLDER");
        config.put("prompt.system.append", "SYSTEM APPEND");

        ReviewConfig reviewConfig = factory.from(config);

        assertEquals("CHUNK WITHOUT PLACEHOLDER\nADDITIONAL INSTRUCTIONS:\nCHUNK APPEND",
                reviewConfig.getPromptTemplates().getChunkInstructionsTemplate());
        assertEquals("SYSTEM WITHOUT PLACEHOLDER\nADDITIONAL INSTRUCTIONS:\nSYSTEM APPEND",
                reviewConfig.getPromptTemplates().getSystemPrompt());
    }

    @Test
    public void appliesAppendsToDefaultTemplatesWhenNoOverridesProvided() {
        Map<String, Object> config = new HashMap<>();
        config.put("prompt.system.append", "SYSTEM APPEND");
        config.put("prompt.chunk.append", "CHUNK APPEND");

        ReviewConfig reviewConfig = factory.from(config);

        String systemPrompt = reviewConfig.getPromptTemplates().getSystemPrompt();
        String chunkPrompt = reviewConfig.getPromptTemplates().getChunkInstructionsTemplate();

        assertTrue(systemPrompt.contains("ADDITIONAL INSTRUCTIONS:"));
        assertTrue(systemPrompt.contains("SYSTEM APPEND"));
        assertTrue(chunkPrompt.contains("ADDITIONAL INSTRUCTIONS:"));
        assertTrue(chunkPrompt.contains("CHUNK APPEND"));
    }

    @Test
    public void onlyChunkAppendDoesNotAffectSystemPrompt() {
        Map<String, Object> baseConfig = new HashMap<>();
        baseConfig.put("prompt.chunk", "BASE CHUNK");
        baseConfig.put("prompt.system", "BASE SYSTEM");

        ReviewConfig baseReviewConfig = factory.from(baseConfig);

        Map<String, Object> config = new HashMap<>(baseConfig);
        config.put("prompt.chunk.append", "CHUNK ONLY APPEND");

        ReviewConfig reviewConfig = factory.from(config);

        assertEquals("BASE CHUNK\nADDITIONAL INSTRUCTIONS:\nCHUNK ONLY APPEND",
                reviewConfig.getPromptTemplates().getChunkInstructionsTemplate());
        assertEquals(baseReviewConfig.getPromptTemplates().getSystemPrompt(),
                reviewConfig.getPromptTemplates().getSystemPrompt());
    }

    @Test
    public void onlySystemAppendDoesNotAffectChunkPrompt() {
        Map<String, Object> baseConfig = new HashMap<>();
        baseConfig.put("prompt.chunk", "BASE CHUNK");
        baseConfig.put("prompt.system", "BASE SYSTEM");

        ReviewConfig baseReviewConfig = factory.from(baseConfig);

        Map<String, Object> config = new HashMap<>(baseConfig);
        config.put("prompt.system.append", "SYSTEM ONLY APPEND");

        ReviewConfig reviewConfig = factory.from(config);

        assertEquals(baseReviewConfig.getPromptTemplates().getChunkInstructionsTemplate(),
                reviewConfig.getPromptTemplates().getChunkInstructionsTemplate());
        assertEquals("BASE SYSTEM\nADDITIONAL INSTRUCTIONS:\nSYSTEM ONLY APPEND",
                reviewConfig.getPromptTemplates().getSystemPrompt());
    }

    @Test
    public void verboseModeDefaultsToFalseWhenAbsent() {
        Map<String, Object> config = new HashMap<>();

        ReviewConfig reviewConfig = factory.from(config);

        assertFalse(reviewConfig.isVerboseMode());
    }

    @Test
    public void verboseModeIsTrueWhenConfigured() {
        Map<String, Object> config = new HashMap<>();
        config.put("verboseMode", true);

        ReviewConfig reviewConfig = factory.from(config);

        assertTrue(reviewConfig.isVerboseMode());
    }

    @Test
    public void reviewModeFullOverridesChunkingAndProfile() {
        Map<String, Object> config = new HashMap<>();
        config.put("reviewMode", "full");

        ReviewConfig reviewConfig = factory.from(config);

        assertEquals(ReviewMode.FULL, reviewConfig.getReviewMode());
        assertEquals(100000, reviewConfig.getMaxCharsPerChunk());
        assertEquals(6, reviewConfig.getMaxFilesPerChunk());
        assertEquals(40, reviewConfig.getMaxChunks());
        assertEquals(SeverityLevel.LOW, reviewConfig.getProfile().getMinSeverity());
        assertTrue(reviewConfig.getProfile().isReviewTests());
        assertFalse(reviewConfig.getProfile().isSkipGeneratedFiles());
        assertEquals(100, reviewConfig.getProfile().getMaxIssuesPerFile());
    }

    @Test
    public void readsRagEmbeddingModelFromConfig() {
        Map<String, Object> config = new HashMap<>();
        config.put("ragEmbeddingModel", "bge-m3");

        ReviewConfig reviewConfig = factory.from(config);

        assertEquals("bge-m3", reviewConfig.getRagEmbeddingModel());
    }
}
