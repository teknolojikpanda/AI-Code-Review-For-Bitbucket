package com.teknolojikpanda.bitbucket.aireviewer.util;

/**
 * Represents statistics for a file change in a diff.
 *
 * Tracks the number of lines added and deleted in a file.
 */
public class FileChange {

    private final String path;
    private final int additions;
    private final int deletions;

    public FileChange(String path, int additions, int deletions) {
        this.path = path;
        this.additions = additions;
        this.deletions = deletions;
    }

    public String getPath() {
        return path;
    }

    public int getAdditions() {
        return additions;
    }

    public int getDeletions() {
        return deletions;
    }

    public int getTotalChanges() {
        return additions + deletions;
    }

    @Override
    public String toString() {
        return String.format("%s (+%d, -%d)", path, additions, deletions);
    }
}
