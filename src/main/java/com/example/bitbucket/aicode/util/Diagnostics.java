package com.example.bitbucket.aicode.util;

import com.example.bitbucket.aicode.model.ReviewChunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Lightweight diagnostics helper that can be toggled via the JVM system property
 * {@code ai.reviewer.diagnostics=true}.
 *
 * When enabled, detailed trace logs and diff/chunk dumps are written under
 * {@code ${java.io.tmpdir}/ai-reviewer/}.
 */
public final class Diagnostics {

    private static final Logger log = LoggerFactory.getLogger(Diagnostics.class);
    private static final String TRACE_PROPERTY = "ai.reviewer.diagnostics";
    private static final Path ROOT_DIR = Paths.get(System.getProperty("java.io.tmpdir"), "ai-reviewer");
    private static final SimpleDateFormat TIMESTAMP_FMT = new SimpleDateFormat("yyyyMMdd-HHmmss");

    private Diagnostics() {
    }

    /**
     * Returns true when diagnostics are globally enabled.
     */
    public static boolean isEnabled() {
        return Boolean.parseBoolean(System.getProperty(TRACE_PROPERTY, "false"));
    }

    /**
     * Emits a diagnostic log line if tracing is enabled.
     */
    public static void log(Logger logger, Supplier<String> messageSupplier) {
        Objects.requireNonNull(logger, "logger");
        Objects.requireNonNull(messageSupplier, "messageSupplier");
        if (isEnabled()) {
            logger.info("[DIAG] {}", messageSupplier.get());
        } else if (logger.isDebugEnabled()) {
            logger.debug("[diag-disabled] {}", messageSupplier.get());
        }
    }

    /**
     * Writes the raw unified diff to disk for later inspection.
     */
    public static void dumpRawDiff(long pullRequestId, String diffContent) {
        dumpToFile("pr-" + pullRequestId, "raw.diff", diffContent);
    }

    /**
     * Writes per-file diff content to disk.
     */
    public static void dumpFileDiff(long pullRequestId, String filePath, String diffContent) {
        String sanitized = sanitize(filePath) + ".diff";
        dumpToFile("pr-" + pullRequestId + "/files", sanitized, diffContent);
    }

    /**
     * Writes chunk content to disk for diagnostics.
     */
    public static void dumpChunk(long pullRequestId, ReviewChunk chunk) {
        if (chunk == null) {
            return;
        }
        String filename = String.format("%02d-%s.diff", chunk.getIndex(), sanitize(chunk.getId()));
        dumpToFile("pr-" + pullRequestId + "/chunks", filename, chunk.getContent());
    }

    private static void dumpToFile(String namespace, String filename, String content) {
        if (!isEnabled() || content == null) {
            return;
        }

        try {
            Path dir = ROOT_DIR.resolve(namespace);
            Files.createDirectories(dir);

            // Prepend timestamp to avoid accidental overwrites when multiple runs exist.
            String timestamp = TIMESTAMP_FMT.format(new Date());
            Path file = dir.resolve(timestamp + "-" + filename);
            Files.writeString(
                    file,
                    content,
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE);
            log.debug("Diagnostic dump written to {}", file);
        } catch (IOException e) {
            log.warn("Failed to write diagnostics file {}/{}: {}", namespace, filename, e.getMessage());
        }
    }

    private static String sanitize(String value) {
        if (value == null || value.isEmpty()) {
            return "unknown";
        }
        return value.replace(':', '_')
                .replace('\\', '_')
                .replace('/', '_')
                .replace(' ', '_')
                .replaceAll("[^a-zA-Z0-9._-]", "_");
    }
}

