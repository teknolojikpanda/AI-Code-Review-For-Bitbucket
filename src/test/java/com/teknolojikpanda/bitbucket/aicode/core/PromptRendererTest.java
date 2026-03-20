package com.teknolojikpanda.bitbucket.aicode.core;

import com.atlassian.bitbucket.pull.PullRequest;
import com.teknolojikpanda.bitbucket.aicode.model.LineRange;
import com.teknolojikpanda.bitbucket.aicode.model.PromptTemplates;
import com.teknolojikpanda.bitbucket.aicode.model.ReviewChunk;
import com.teknolojikpanda.bitbucket.aicode.model.ReviewConfig;
import com.teknolojikpanda.bitbucket.aicode.model.ReviewContext;
import com.teknolojikpanda.bitbucket.aicode.model.ReviewMode;
import com.teknolojikpanda.bitbucket.aicode.model.ReviewOverview;
import com.teknolojikpanda.bitbucket.aicode.model.ReviewProfile;
import org.junit.Test;

import java.net.URI;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;

public class PromptRendererTest {

    @Test
    public void renderChunkInstructionsIncludesAstAndRagSections() throws Exception {
        PromptTemplates templates = PromptTemplates.builder()
                .systemPrompt("SYS")
                .overviewTemplate("OV")
                .overviewFileEntryTemplate("FILE")
                .impactSummaryTemplate("IMPACT {{OVERVIEW}}")
                .chunkInstructionsTemplate("AST={{AST_CONTEXT}}\nRAG={{RAG_EVIDENCE}}\nGUIDE={{REASONING_GUIDE}}\nCTX={{CHUNK_CONTEXT}}")
                .build();

        ReviewConfig config = ReviewConfig.builder()
                .primaryModelEndpoint(new URI("http://primary"))
                .primaryModel("model")
                .fallbackModelEndpoint(new URI("http://fallback"))
                .fallbackModel("fallback")
                .reviewMode(ReviewMode.DEEP)
                .profile(ReviewProfile.builder().build())
                .build();

        Map<String, ReviewOverview.FileStats> stats = new LinkedHashMap<>();
        stats.put("src/main/java/app/UserService.java", new ReviewOverview.FileStats(12, 2, false));
        stats.put("src/main/java/app/UserRepository.java", new ReviewOverview.FileStats(8, 1, false));

        Map<String, String> diffs = new LinkedHashMap<>();
        diffs.put("src/main/java/app/UserService.java",
                "+ public class UserService {\n+ public Result calculatePrice(User user) {\n+   return pricingEngine.compute(user);\n+ }\n");
        diffs.put("src/main/java/app/UserRepository.java",
                "+ return cache.get(user.getId());\n+ pricingEngine.compute(user);\n");

        ReviewContext context = ReviewContext.builder()
                .pullRequest(mock(PullRequest.class))
                .config(config)
                .rawDiff("diff")
                .fileStats(stats)
                .fileDiffs(diffs)
                .fileMetadata(Collections.emptyMap())
                .collectedAt(Instant.now())
                .build();

        ReviewChunk chunk = ReviewChunk.builder()
                .id("c-1")
                .index(1)
                .content("+ public Result calculatePrice(User user) {\n+   return pricingEngine.compute(user);\n+ }")
                .files(List.of("src/main/java/app/UserService.java"))
                .primaryRanges(Map.of("src/main/java/app/UserService.java", List.of(LineRange.of(10, 20))))
                .build();

        String rendered = PromptRenderer.renderChunkInstructions(
                templates,
                config,
                context,
                chunk,
                "overview",
                "impact",
                "[Line 10] + return pricingEngine.compute(user);"
        );

        assertTrue(rendered.contains("AST=- key symbols:"));
        assertTrue(rendered.contains("calculatePrice"));
        assertTrue(rendered.contains("RAG=- ["));
        assertTrue(rendered.contains("score="));
        assertTrue(rendered.contains("GUIDE=1. Start from concrete evidence lines before making claims."));
    }

    @Test
    public void renderChunkInstructionsHandlesMissingEvidence() throws Exception {
        PromptTemplates templates = PromptTemplates.builder()
                .systemPrompt("SYS")
                .overviewTemplate("OV")
                .overviewFileEntryTemplate("FILE")
                .impactSummaryTemplate("IMPACT")
                .chunkInstructionsTemplate("AST={{AST_CONTEXT}}\nRAG={{RAG_EVIDENCE}}")
                .build();

        ReviewConfig config = ReviewConfig.builder()
                .primaryModelEndpoint(new URI("http://primary"))
                .primaryModel("model")
                .fallbackModelEndpoint(new URI("http://fallback"))
                .fallbackModel("fallback")
                .reviewMode(ReviewMode.STANDARD)
                .profile(ReviewProfile.builder().build())
                .build();

        ReviewContext context = ReviewContext.builder()
                .pullRequest(mock(PullRequest.class))
                .config(config)
                .rawDiff("diff")
                .fileStats(Collections.emptyMap())
                .fileDiffs(Collections.emptyMap())
                .fileMetadata(Collections.emptyMap())
                .collectedAt(Instant.now())
                .build();

        ReviewChunk chunk = ReviewChunk.builder()
                .id("c-empty")
                .index(0)
                .content("+ // no identifiers")
                .files(Collections.emptyList())
                .primaryRanges(Collections.emptyMap())
                .build();

        String rendered = PromptRenderer.renderChunkInstructions(
                templates,
                config,
                context,
                chunk,
                "overview",
                "impact",
                "[Line 1] + // no identifiers"
        );

                assertTrue(rendered.contains("AST=- (no strong AST-like signals detected in chunk)"));
                assertTrue(rendered.contains("RAG=- ("));
    }
}
