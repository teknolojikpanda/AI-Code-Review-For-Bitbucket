package com.example.bitbucket.aireviewer.util;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Represents a chunk of diff content for processing.
 *
 * Large diffs are split into multiple chunks to fit within
 * Ollama's token limits and to enable parallel processing.
 */
public class DiffChunk {

    private final String content;
    private final List<String> files;
    private final int size;
    private final int index;

    private DiffChunk(Builder builder) {
        this.content = builder.content;
        this.files = Collections.unmodifiableList(new ArrayList<>(builder.files));
        this.size = builder.size;
        this.index = builder.index;
    }

    @Nonnull
    public String getContent() {
        return content;
    }

    @Nonnull
    public List<String> getFiles() {
        return files;
    }

    public int getSize() {
        return size;
    }

    public int getIndex() {
        return index;
    }

    @Override
    public String toString() {
        return String.format("DiffChunk{index=%d, files=%d, size=%d chars}",
                index, files.size(), size);
    }

    @Nonnull
    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String content = "";
        private final List<String> files = new ArrayList<>();
        private int size = 0;
        private int index = 0;

        private Builder() {
        }

        @Nonnull
        public Builder content(@Nonnull String content) {
            this.content = content;
            this.size = content.length();
            return this;
        }

        @Nonnull
        public Builder addFile(@Nonnull String file) {
            this.files.add(file);
            return this;
        }

        @Nonnull
        public Builder files(@Nonnull List<String> files) {
            this.files.clear();
            this.files.addAll(files);
            return this;
        }

        @Nonnull
        public Builder size(int size) {
            this.size = size;
            return this;
        }

        @Nonnull
        public Builder index(int index) {
            this.index = index;
            return this;
        }

        @Nonnull
        public DiffChunk build() {
            Objects.requireNonNull(content, "content cannot be null");
            return new DiffChunk(this);
        }
    }
}
