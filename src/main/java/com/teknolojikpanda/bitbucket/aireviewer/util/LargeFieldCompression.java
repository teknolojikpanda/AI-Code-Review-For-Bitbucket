package com.teknolojikpanda.bitbucket.aireviewer.util;

import javax.annotation.Nullable;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Utility for compressing large JSON blobs that would otherwise bloat AO tables.
 * Adds a lightweight prefix so old, uncompressed data can still be read without migration.
 */
public final class LargeFieldCompression {

    private static final String PREFIX = "gz:";
    private static final int MIN_LENGTH = 1_024;

    private LargeFieldCompression() {
    }

    /**
     * Compresses the provided string when it is large enough to benefit from compression.
     *
     * @param input raw JSON string
     * @return compressed payload with prefix, or the original input when compression is not applied
     */
    @Nullable
    public static String compress(@Nullable String input) {
        if (input == null) {
            return null;
        }
        if (input.isEmpty() || input.length() < MIN_LENGTH || isCompressed(input)) {
            return input;
        }
        byte[] utf8 = input.getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream baos = new ByteArrayOutputStream(utf8.length);
        try (GZIPOutputStream gzip = new GZIPOutputStream(baos)) {
            gzip.write(utf8);
        } catch (IOException ex) {
            return input;
        }
        byte[] compressed = baos.toByteArray();
        if (compressed.length >= utf8.length) {
            return input;
        }
        String encoded = Base64.getEncoder().encodeToString(compressed);
        return PREFIX + encoded;
    }

    /**
     * Decompresses the provided payload if it carries the compression prefix.
     *
     * @param input payload possibly compressed by {@link #compress(String)}
     * @return decompressed JSON string or the original input when not compressed
     */
    @Nullable
    public static String decompress(@Nullable String input) {
        if (!isCompressed(input)) {
            return input;
        }
        String encoded = input.substring(PREFIX.length());
        byte[] compressed;
        try {
            compressed = Base64.getDecoder().decode(encoded);
        } catch (IllegalArgumentException ex) {
            return input;
        }
        try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(compressed));
             ByteArrayOutputStream baos = new ByteArrayOutputStream(compressed.length * 2)) {
            byte[] buffer = new byte[512];
            int read;
            while ((read = gzip.read(buffer)) != -1) {
                baos.write(buffer, 0, read);
            }
            return baos.toString(StandardCharsets.UTF_8.name());
        } catch (IOException ex) {
            return input;
        }
    }

    public static boolean isCompressed(@Nullable String input) {
        return input != null && input.startsWith(PREFIX);
    }
}
