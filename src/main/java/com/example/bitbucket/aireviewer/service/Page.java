package com.example.bitbucket.aireviewer.service;

import javax.annotation.Nonnull;
import java.util.Collections;
import java.util.List;

/**
 * Generic pagination container for REST responses.
 */
public final class Page<T> {

    private final List<T> values;
    private final int total;
    private final int limit;
    private final int offset;

    public Page(@Nonnull List<T> values, int total, int limit, int offset) {
        this.values = Collections.unmodifiableList(values);
        this.total = Math.max(total, 0);
        this.limit = Math.max(limit, 0);
        this.offset = Math.max(offset, 0);
    }

    @Nonnull
    public List<T> getValues() {
        return values;
    }

    public int getTotal() {
        return total;
    }

    public int getLimit() {
        return limit;
    }

    public int getOffset() {
        return offset;
    }
}
