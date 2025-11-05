package com.teknolojikpanda.bitbucket.aireviewer.util;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Result of PR size validation.
 *
 * Contains validation result and size metrics.
 */
public class PRSizeValidation {

    private final boolean valid;
    private final String message;
    private final double sizeMB;
    private final int lines;
    private final int sizeBytes;

    private PRSizeValidation(boolean valid, String message, int sizeBytes, double sizeMB, int lines) {
        this.valid = valid;
        this.message = message;
        this.sizeBytes = sizeBytes;
        this.sizeMB = sizeMB;
        this.lines = lines;
    }

    public boolean isValid() {
        return valid;
    }

    @Nullable
    public String getMessage() {
        return message;
    }

    public double getSizeMB() {
        return sizeMB;
    }

    public int getLines() {
        return lines;
    }

    public int getSizeBytes() {
        return sizeBytes;
    }

    @Nonnull
    public static PRSizeValidation valid(int sizeBytes, double sizeMB, int lines) {
        return new PRSizeValidation(true, null, sizeBytes, sizeMB, lines);
    }

    @Nonnull
    public static PRSizeValidation invalid(@Nonnull String message, int sizeBytes, double sizeMB, int lines) {
        return new PRSizeValidation(false, message, sizeBytes, sizeMB, lines);
    }

    @Override
    public String toString() {
        return valid
                ? String.format("Valid (%.2f MB, %d lines)", sizeMB, lines)
                : String.format("Invalid: %s (%.2f MB, %d lines)", message, sizeMB, lines);
    }
}
