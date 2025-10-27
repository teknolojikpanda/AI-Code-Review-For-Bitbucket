package com.example.bitbucket.aireviewer.service;

import com.example.bitbucket.aireviewer.dto.ReviewIssue;
import org.junit.Test;
import static org.junit.Assert.*;

/**
 * Test class to demonstrate multiline commenting functionality.
 * This test verifies that ReviewIssue properly handles line ranges
 * for multiline commenting support in Bitbucket 9.6.5.
 */
public class MultilineCommentTest {

    @Test
    public void testSingleLineIssue() {
        ReviewIssue issue = ReviewIssue.builder()
                .path("src/main/java/Example.java")
                .lineStart(42)
                .severity(ReviewIssue.Severity.HIGH)
                .type("security")
                .summary("Potential SQL injection vulnerability")
                .details("User input is not properly sanitized before database query")
                .fix("Use parameterized queries or prepared statements")
                .build();

        assertEquals("src/main/java/Example.java", issue.getPath());
        assertEquals(Integer.valueOf(42), issue.getLineStart());
        assertNull("Single line issue should not have lineEnd", issue.getLineEnd());
        assertEquals("42", issue.getLineRangeDisplay());
        assertEquals(ReviewIssue.Severity.HIGH, issue.getSeverity());
    }

    @Test
    public void testMultilineIssue() {
        ReviewIssue issue = ReviewIssue.builder()
                .path("src/main/java/Example.java")
                .lineStart(42)
                .lineEnd(47)
                .severity(ReviewIssue.Severity.MEDIUM)
                .type("code-quality")
                .summary("Complex method should be refactored")
                .details("This method spans multiple lines and has high complexity")
                .fix("Break down into smaller methods")
                .build();

        assertEquals("src/main/java/Example.java", issue.getPath());
        assertEquals(Integer.valueOf(42), issue.getLineStart());
        assertEquals(Integer.valueOf(47), issue.getLineEnd());
        assertEquals("42-47", issue.getLineRangeDisplay());
        assertEquals(ReviewIssue.Severity.MEDIUM, issue.getSeverity());
    }

    @Test
    public void testLineRangeBuilder() {
        ReviewIssue issue = ReviewIssue.builder()
                .path("src/main/java/Example.java")
                .lineRange(10, 15)
                .severity(ReviewIssue.Severity.LOW)
                .type("performance")
                .summary("Inefficient loop structure")
                .build();

        assertEquals(Integer.valueOf(10), issue.getLineStart());
        assertEquals(Integer.valueOf(15), issue.getLineEnd());
        assertEquals("10-15", issue.getLineRangeDisplay());
    }

    @Test
    public void testBackwardCompatibilityWithDeprecatedLine() {
        ReviewIssue issue = ReviewIssue.builder()
                .path("src/main/java/Example.java")
                .line(25)  // Using deprecated line method
                .severity(ReviewIssue.Severity.INFO)
                .type("style")
                .summary("Code style issue")
                .build();

        assertEquals("Fallback to line value expected", Integer.valueOf(25), issue.getLineStart());
        assertNull(issue.getLineEnd());
        assertEquals("25", issue.getLineRangeDisplay());
    }

    @Test
    public void testMixedLineAndLineStart() {
        // Test when both line and lineStart are set (lineStart should take precedence)
        ReviewIssue issue = ReviewIssue.builder()
                .path("src/main/java/Example.java")
                .line(20)  // Deprecated
                .lineStart(30)  // New preferred method
                .lineEnd(35)
                .severity(ReviewIssue.Severity.CRITICAL)
                .type("bug")
                .summary("Critical bug in multiline block")
                .build();

        assertEquals(Integer.valueOf(30), issue.getLineStart());
        assertEquals(Integer.valueOf(35), issue.getLineEnd());
        assertEquals("30-35", issue.getLineRangeDisplay());
        // API should report the explicit range regardless of deprecated setters
        assertEquals(Integer.valueOf(30), issue.getLineStart());
    }

    @Test
    public void testNoLineInformation() {
        ReviewIssue issue = ReviewIssue.builder()
                .path("src/main/java/Example.java")
                .severity(ReviewIssue.Severity.LOW)
                .type("general")
                .summary("File-level issue")
                .build();

        assertNull(issue.getLineStart());
        assertNull(issue.getLineEnd());
        assertEquals("?", issue.getLineRangeDisplay());
    }
}
