package com.teknolojikpanda.bitbucket.aicode.model;

import javax.annotation.Nonnull;
import java.util.Objects;

/**
 * Represents a 1-based inclusive line range.
 */
public final class LineRange {

    private final int start;
    private final int end;

    private LineRange(int start, int end) {
        if (start < 1) {
            throw new IllegalArgumentException("start must be >= 1");
        }
        if (end < start) {
            throw new IllegalArgumentException("end must be >= start");
        }
        this.start = start;
        this.end = end;
    }

    public static LineRange of(int start, int end) {
        return new LineRange(start, end);
    }

    public static LineRange singleLine(int line) {
        return new LineRange(line, line);
    }

    public int getStart() {
        return start;
    }

    public int getEnd() {
        return end;
    }

    @Nonnull
    public String asDisplay() {
        return start == end ? String.valueOf(start) : start + "-" + end;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof LineRange)) return false;
        LineRange that = (LineRange) o;
        return start == that.start && end == that.end;
    }

    @Override
    public int hashCode() {
        return Objects.hash(start, end);
    }

    @Override
    public String toString() {
        return asDisplay();
    }
}
