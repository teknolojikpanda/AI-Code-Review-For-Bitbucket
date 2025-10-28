package com.example.bitbucket.aicode.core;

import com.example.bitbucket.aicode.core.DiffPositionResolver.DiffPositionIndex;
import org.junit.Test;

import static org.junit.Assert.*;

public class DiffPositionResolverTest {

    private static final String SAMPLE_DIFF = "diff --git a/foo.txt b/foo.txt\n" +
            "index 123..456 100644\n" +
            "--- a/foo.txt\n" +
            "+++ b/foo.txt\n" +
            "@@ -1,3 +1,4 @@\n" +
            " line-one\n" +
            "-line-two\n" +
            "+line-two-updated\n" +
            " line-three\n" +
            "+line-four\n";

    @Test
    public void indexesAddedAndContextLines() {
        DiffPositionIndex index = DiffPositionResolver.index(SAMPLE_DIFF);
        assertTrue(index.containsLine(1)); // context
        assertTrue(index.containsLine(2)); // added line
        assertTrue(index.containsLine(3)); // context
        assertTrue(index.containsLine(4)); // added line

        assertEquals(DiffPositionResolver.LineType.CONTEXT, index.getLineType(1));
        assertEquals(DiffPositionResolver.LineType.ADDED, index.getLineType(2));
    }

    @Test
    public void ignoresRemovedLines() {
        DiffPositionIndex index = DiffPositionResolver.index(SAMPLE_DIFF);
        assertFalse(index.containsLine(0));
    }
}
