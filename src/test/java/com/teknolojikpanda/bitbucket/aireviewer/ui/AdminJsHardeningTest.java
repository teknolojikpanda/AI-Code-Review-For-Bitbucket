package com.teknolojikpanda.bitbucket.aireviewer.ui;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class AdminJsHardeningTest {

    @Test
    public void scopeTreeUnavailableShowsDeterministicErrorAndDisablesControls() throws IOException {
        String js = readAdminScript();

        assertTrue(js.contains("data-scope-error=\"missing-dependency\""));
        assertTrue(js.contains("Repository scope is unavailable: required ScopeTree module did not load."));
        assertTrue(js.contains("setScopeControlsDisabled(true);"));
        assertTrue(js.contains("$('#repository-scope-tree .scope-checkbox').prop('disabled', isDisabled);"));
        assertTrue(js.contains("$('#repository-overrides-body .override-toggle').prop('disabled', isDisabled);"));
        assertTrue(js.contains("$('#guardrails-scope-manage')"));
        assertTrue(js.contains(".prop('disabled', isDisabled)"));
    }

    @Test
    public void productionModeAvoidsDirectConsoleLogCalls() throws IOException {
        String js = readAdminScript();

        assertTrue(js.contains("function debugLog()"));
        assertTrue(js.contains("if (!debugEnabled || !window.console || typeof console.log !== 'function')"));
        assertTrue(js.contains("console.log.apply(console, arguments);"));
        assertFalse(js.contains("console.log('"));
    }

    @Test
    public void saveFailureParsesErrorPayloadAndValidationDetails() throws IOException {
        String js = readAdminScript();

        assertTrue(js.contains("extractApiErrorMessage(xhr, error, 'Failed to save configuration')"));
        assertTrue(js.contains("var baseMessage = payload.error || payload.message;"));
        assertTrue(js.contains("var detailsText = formatValidationDetails(payload.details);"));
        assertTrue(js.contains("parts.push(key + ': ' + text);"));
    }

    private String readAdminScript() throws IOException {
        return Files.readString(
                Paths.get("src/main/resources/js/ai-reviewer-admin.js"),
                StandardCharsets.UTF_8
        );
    }
}
