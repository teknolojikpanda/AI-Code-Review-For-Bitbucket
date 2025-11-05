package com.teknolojikpanda.bitbucket.aicode.core;

import com.teknolojikpanda.bitbucket.aicode.model.ReviewFileMetadata;
import com.teknolojikpanda.bitbucket.aicode.model.ReviewOverview;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.*;

public class FileMetadataExtractorTest {

    @Test
    public void extractsLanguageDirectoryAndFlags() {
        Map<String, ReviewOverview.FileStats> stats = new HashMap<>();
        stats.put("server/src/main/java/org/example/security/AuthService.java", new ReviewOverview.FileStats(10, 2, false));
        stats.put("web/test/LoginForm.test.tsx", new ReviewOverview.FileStats(5, 5, false));

        Map<String, ReviewFileMetadata> metadata = FileMetadataExtractor.extract(stats);

        ReviewFileMetadata authMeta = metadata.get("server/src/main/java/org/example/security/AuthService.java");
        assertNotNull(authMeta);
        assertEquals("server/src/main/java/org/example/security", authMeta.getDirectory());
        assertEquals("java", authMeta.getExtension());
        assertEquals("Java", authMeta.getLanguage());
        assertEquals(12, authMeta.getTotalChanges());
        assertFalse(authMeta.isTestFile());

        ReviewFileMetadata testMeta = metadata.get("web/test/LoginForm.test.tsx");
        assertNotNull(testMeta);
        assertEquals("tsx", testMeta.getExtension());
        assertEquals("TypeScript React", testMeta.getLanguage());
        assertTrue(testMeta.isTestFile());
    }
}
