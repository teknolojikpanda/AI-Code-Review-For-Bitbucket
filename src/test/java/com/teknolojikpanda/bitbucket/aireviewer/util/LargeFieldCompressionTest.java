package com.teknolojikpanda.bitbucket.aireviewer.util;

import org.junit.Test;

import static org.junit.Assert.*;

public class LargeFieldCompressionTest {

    @Test
    public void smallPayloadsRemainUnchanged() {
        String payload = "tiny";
        String compressed = LargeFieldCompression.compress(payload);
        assertEquals("Small payload should bypass compression", payload, compressed);
    }

    @Test
    public void roundTripRestoresOriginalValue() {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < 2_000; i++) {
            builder.append('a' + (i % 26));
        }
        String payload = builder.toString();
        String compressed = LargeFieldCompression.compress(payload);
        assertNotEquals("Compression should add prefix", payload, compressed);
        assertTrue("Compressed payload must advertise prefix", LargeFieldCompression.isCompressed(compressed));
        assertEquals(payload, LargeFieldCompression.decompress(compressed));
    }

    @Test
    public void invalidPayloadGracefullyFallsBack() {
        String payload = "gz:not-base64";
        assertEquals(payload, LargeFieldCompression.decompress(payload));
    }
}
