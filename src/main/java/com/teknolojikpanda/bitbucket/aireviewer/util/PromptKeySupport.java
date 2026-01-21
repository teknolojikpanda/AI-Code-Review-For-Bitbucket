package com.teknolojikpanda.bitbucket.aireviewer.util;

import java.util.Locale;
import java.util.Set;

public final class PromptKeySupport {

    private static final Set<String> SUPPORTED_PROMPT_KEYS = Set.of(
            "prompt.system",
            "prompt.chunk",
            "prompt.overview",
            "prompt.fileline",
            "prompt.overviewfile",
            "prompt.system.append",
            "prompt.chunk.append"
    );

    private PromptKeySupport() {
    }

    public static boolean isPromptKey(String key) {
        if (key == null) {
            return false;
        }
        return SUPPORTED_PROMPT_KEYS.contains(key.trim().toLowerCase(Locale.ROOT));
    }
}