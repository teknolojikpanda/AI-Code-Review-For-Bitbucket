package com.example.bitbucket.aicode.core;

import javax.annotation.Nonnull;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * Parses unified diff hunks and maps destination line numbers to line types.
 */
public final class DiffPositionResolver {

    private DiffPositionResolver() {
    }

    public static DiffPositionIndex index(@Nonnull String diffContent) {
        Objects.requireNonNull(diffContent, "diffContent");
        Map<Integer, LineType> lines = new LinkedHashMap<>();
        String[] rawLines = diffContent.split("\n", -1);
        int currentDestLine = 0;
        boolean inHunk = false;

        for (String rawLine : rawLines) {
            if (rawLine.startsWith("diff --git")) {
                inHunk = false;
                currentDestLine = 0;
                continue;
            }

            if (rawLine.startsWith("@@")) {
                Integer parsed = parseDestStart(rawLine);
                if (parsed != null) {
                    currentDestLine = parsed;
                    inHunk = true;
                } else {
                    inHunk = false;
                }
                continue;
            }

            if (!inHunk) {
                continue;
            }

            if (rawLine.startsWith("+")) {
                if (rawLine.startsWith("+++")) {
                    continue;
                }
                lines.put(currentDestLine, LineType.ADDED);
                currentDestLine++;
            } else if (rawLine.startsWith("-")) {
                if (rawLine.startsWith("---")) {
                    continue;
                }
                // removal: dest line not advanced
            } else if (rawLine.startsWith("\\")) {
                // "No newline at end of file" marker
            } else {
                lines.put(currentDestLine, LineType.CONTEXT);
                currentDestLine++;
            }
        }
        return new DiffPositionIndex(lines);
    }

    private static Integer parseDestStart(String hunkHeader) {
        for (String token : hunkHeader.split(" ")) {
            if (token.startsWith("+")) {
                String part = token.substring(1);
                String[] segments = part.split(",");
                try {
                    return Integer.parseInt(segments[0]);
                } catch (NumberFormatException ignored) {
                    return null;
                }
            }
        }
        return null;
    }

    public enum LineType {
        ADDED,
        CONTEXT
    }

    public static final class DiffPositionIndex {
        private final Map<Integer, LineType> lineTypes;
        private final Set<Integer> lines;

        private DiffPositionIndex(Map<Integer, LineType> lineTypes) {
            this.lineTypes = Collections.unmodifiableMap(lineTypes);
            this.lines = Collections.unmodifiableSet(new TreeSet<>(lineTypes.keySet()));
        }

        public boolean containsLine(int line) {
            return lineTypes.containsKey(line);
        }

        public LineType getLineType(int line) {
            return lineTypes.get(line);
        }

        public Set<Integer> getLines() {
            return lines;
        }
    }
}
