package com.teknolojikpanda.bitbucket.aireviewer.util;

import org.junit.Test;

import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class ChunkTelemetryUtilTest {

    @Test
    public void extractEntriesFromJson_parsesChunkInvocationData() {
        String json = "{\n" +
                "  \"ai.chunk.invocations\": [\n" +
                "    {\n" +
                "      \"chunkId\": \"chunk-1\",\n" +
                "      \"role\": \"primary\",\n" +
                "      \"model\": \"model-a\",\n" +
                "      \"endpoint\": \"http://ollama\",\n" +
                "      \"attempts\": 1,\n" +
                "      \"retries\": 0,\n" +
                "      \"durationMs\": 1200,\n" +
                "      \"success\": true,\n" +
                "      \"requestBytes\": 256,\n" +
                "      \"responseBytes\": 1024\n" +
                "    },\n" +
                "    {\n" +
                "      \"chunkId\": \"chunk-2\",\n" +
                "      \"role\": \"fallback\",\n" +
                "      \"model\": \"model-b\",\n" +
                "      \"attempts\": 2,\n" +
                "      \"retries\": 1,\n" +
                "      \"durationMs\": 2400,\n" +
                "      \"success\": false,\n" +
                "      \"statusCode\": 500\n" +
                "    }\n" +
                "  ]\n" +
                "}";

        List<Map<String, Object>> entries = ChunkTelemetryUtil.extractEntriesFromJson(json);
        assertEquals(2, entries.size());

        Map<String, Object> first = entries.get(0);
        assertEquals("chunk-1", first.get("chunkId"));
        assertEquals("primary", first.get("role"));
        assertEquals(1, ((Number) first.get("attempts")).intValue());
        assertEquals(256, ((Number) first.get("requestBytes")).intValue());

        Map<String, Object> second = entries.get(1);
        assertEquals("chunk-2", second.get("chunkId"));
        assertNotNull(second.get("statusCode"));
    }

    @Test
    public void extractEntriesSupportsCompressedPayloads() {
        StringBuilder builder = new StringBuilder();
        builder.append("{\"ai.chunk.invocations\":[");
        for (int i = 0; i < 40; i++) {
            if (i > 0) {
                builder.append(',');
            }
            builder.append("{\"chunkId\":\"chunk-").append(i).append("\",\"attempts\":1}");
        }
        builder.append("]}");
        String payload = builder.toString();
        String compressed = LargeFieldCompression.compress(payload);
        assertTrue("payload should be compressed", LargeFieldCompression.isCompressed(compressed));

        List<Map<String, Object>> entries = ChunkTelemetryUtil.extractEntriesFromJson(compressed);
        assertEquals(40, entries.size());
        assertEquals("chunk-0", entries.get(0).get("chunkId"));
    }
}
