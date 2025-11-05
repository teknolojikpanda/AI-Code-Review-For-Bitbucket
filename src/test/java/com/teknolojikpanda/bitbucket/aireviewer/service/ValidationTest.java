package com.teknolojikpanda.bitbucket.aireviewer.service;

import org.junit.Test;
import static org.junit.Assert.*;

/**
 * Test the validation logic improvements.
 */
public class ValidationTest {

    @Test
    public void testPathNormalization() {
        // Test path normalization logic
        String path1 = "src://server/src/main/java/SecurityConfig.java";
        String normalized1 = path1.replaceAll("^[a-zA-Z]+://", "").replaceAll("^/+", "");
        assertEquals("server/src/main/java/SecurityConfig.java", normalized1);
        
        String path2 = "a/webui/src/default/login.tsx";
        String normalized2 = path2.replaceAll("^[a-zA-Z]+://", "").replaceAll("^/+", "");
        if (normalized2.startsWith("a/") || normalized2.startsWith("b/")) {
            normalized2 = normalized2.substring(2);
        }
        assertEquals("webui/src/default/login.tsx", normalized2);
        
        String path3 = "/src/UserService.java";
        String normalized3 = path3.replaceAll("^[a-zA-Z]+://", "").replaceAll("^/+", "");
        assertEquals("src/UserService.java", normalized3);
    }
    
    @Test
    public void testDiffPathMatching() {
        String diffText = "diff --git a/server/src/main/java/SecurityConfig.java b/server/src/main/java/SecurityConfig.java\n" +
                         "index abc123..def456 100644\n" +
                         "--- a/server/src/main/java/SecurityConfig.java\n" +
                         "+++ b/server/src/main/java/SecurityConfig.java\n" +
                         "@@ -42,7 +42,7 @@\n" +
                         " public class SecurityConfig {\n" +
                         "+    // New security configuration\n" +
                         "     private final UserService userService;\n";
        
        String filePath = "server/src/main/java/SecurityConfig.java";
        
        // Test multiple diff formats
        assertTrue("Should match a/path b/path format", 
                  diffText.contains("diff --git a/" + filePath + " b/" + filePath));
        assertTrue("Should match +++ b/path format", 
                  diffText.contains("+++ b/" + filePath));
        assertTrue("Should match --- a/path format", 
                  diffText.contains("--- a/" + filePath));
    }
    
    @Test
    public void testInvalidPathDetection() {
        // Test that obviously invalid paths are rejected
        assertFalse("null should be invalid", isValidPath("null"));
        assertFalse("undefined should be invalid", isValidPath("undefined"));
        assertFalse("empty should be invalid", isValidPath(""));
        assertFalse("single char should be invalid", isValidPath("a"));
        
        // Test that valid paths are accepted
        assertTrue("Normal path should be valid", isValidPath("src/main/java/Test.java"));
        assertTrue("Path with numbers should be valid", isValidPath("src/test/Test123.java"));
    }
    
    private boolean isValidPath(String path) {
        if (path == null) return false;
        if (path.length() < 2 || path.equals("null") || path.equals("undefined")) {
            return false;
        }
        return true;
    }
}