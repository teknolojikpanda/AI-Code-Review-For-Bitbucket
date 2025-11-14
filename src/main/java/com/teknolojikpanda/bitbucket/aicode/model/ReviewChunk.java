package com.teknolojikpanda.bitbucket.aicode.model;

import javax.annotation.Nonnull;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Represents a diff segment processed in a single AI call.
 */
public final class ReviewChunk {

    private final String id;
    private final int index;
    private final String content;
    private final List<String> files;
    private final Map<String, List<LineRange>> primaryRanges;

    private ReviewChunk(Builder builder) {
        this.id = Objects.requireNonNull(builder.id, "id");
        this.index = builder.index;
        this.content = Objects.requireNonNull(builder.content, "content");
        this.files = Collections.unmodifiableList(builder.files);
        Map<String, List<LineRange>> ranges = new LinkedHashMap<>();
        builder.primaryRanges.forEach((file, values) ->
                ranges.put(file, Collections.unmodifiableList(new java.util.ArrayList<>(values))));
        this.primaryRanges = Collections.unmodifiableMap(ranges);
    }

    @Nonnull
    public String getId() {
        return id;
    }

    public int getIndex() {
        return index;
    }

    @Nonnull
    public String getContent() {
        return content;
    }

    @Nonnull
    public List<String> getFiles() {
        return files;
    }

    @Nonnull
    public Map<String, List<LineRange>> getPrimaryRanges() {
        return primaryRanges;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String id;
        private int index;
        private String content = "";
        private List<String> files = new java.util.ArrayList<>();
        private Map<String, List<LineRange>> primaryRanges = new LinkedHashMap<>();

        public Builder id(@Nonnull String id) {
            this.id = Objects.requireNonNull(id, "id");
            return this;
        }

        public Builder index(int index) {
            this.index = index;
            return this;
        }

        public Builder content(@Nonnull String content) {
            this.content = Objects.requireNonNull(content, "content");
            return this;
        }

        public Builder files(@Nonnull List<String> files) {
            this.files = new java.util.ArrayList<>(Objects.requireNonNull(files, "files"));
            return this;
        }

        public Builder addFile(@Nonnull String file) {
            this.files.add(Objects.requireNonNull(file, "file"));
            return this;
        }

        public Builder primaryRanges(@Nonnull Map<String, List<LineRange>> ranges) {
            this.primaryRanges = new LinkedHashMap<>();
            Objects.requireNonNull(ranges, "ranges").forEach((file, values) ->
                    this.primaryRanges.put(file, new java.util.ArrayList<>(values)));
            return this;
        }

        public Builder addPrimaryRange(@Nonnull String file, @Nonnull LineRange range) {
            String key = Objects.requireNonNull(file, "file");
            LineRange value = Objects.requireNonNull(range, "range");
            this.primaryRanges.computeIfAbsent(key, ignored -> new java.util.ArrayList<>()).add(value);
            return this;
        }

        public ReviewChunk build() {
            return new ReviewChunk(this);
        }
    }
}
