package com.teknolojikpanda.bitbucket.aireviewer.ui;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

import static org.junit.Assert.assertTrue;

public class HealthJsPipelineStatusTest {

    @Test
    public void healthDashboardRendersPipelineStatusCards() throws IOException {
        String js = Files.readString(
                Paths.get("src/main/resources/js/ai-reviewer-health.js"),
                StandardCharsets.UTF_8
        );

        assertTrue(js.contains("renderAiPipeline(data.aiPipeline || {})"));
        assertTrue(js.contains("renderPipelineComponent('ast', 'ast', pipeline.ast || {})"));
        assertTrue(js.contains("renderPipelineComponent('rag', 'rag', pipeline.rag || {})"));
        assertTrue(js.contains("renderPipelineComponent('llm', 'llmReasoning', pipeline.llmReasoning || {})"));
        assertTrue(js.contains("normalizePipelineStatus"));
        assertTrue(js.contains("composePipelineSummary"));
        assertTrue(js.contains("renderPipelineActions"));
        assertTrue(js.contains("pipelineFriendlyMessage"));
        assertTrue(js.contains("rawDetail || 'AST context injection is enabled in the chunk prompt template.'"));
        assertTrue(js.contains("#health-pipeline-summary"));
        assertTrue(js.contains("#health-pipeline-actions"));
    }
}
