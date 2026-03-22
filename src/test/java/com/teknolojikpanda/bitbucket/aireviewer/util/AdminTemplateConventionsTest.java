package com.teknolojikpanda.bitbucket.aireviewer.util;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertTrue;

public class AdminTemplateConventionsTest {

    private static final Path TEMPLATE_ROOT = Paths.get("src/main/resources/templates");
    private static final List<String> ADMIN_TEMPLATES = Arrays.asList(
            "admin-config.vm",
            "admin-history.vm",
            "admin-health.vm",
            "admin-ops.vm"
    );

    @Test
    public void adminTemplatesAvoidInlineStylesAndUseSharedLayoutClasses() throws IOException {
        for (String template : ADMIN_TEMPLATES) {
            String content = Files.readString(TEMPLATE_ROOT.resolve(template), StandardCharsets.UTF_8);

            assertTrue(template + " must not contain inline style attributes", !content.contains("style="));
            assertTrue(template + " must include ai-admin-page on body", content.contains("<body class=\"ai-admin-page"));
            assertTrue(template + " must use ai-admin-header class", content.contains("ai-admin-header"));
        }
    }
}
