package com.teknolojikpanda.bitbucket.aireviewer.util;

import com.teknolojikpanda.bitbucket.aicode.model.PromptTemplates;

public final class PromptKeySupport {

    private PromptKeySupport() {
    }

    public static boolean isPromptKey(String key) {
        return canonicalPromptKey(key) != null;
    }

    public static String canonicalPromptKey(String key) {
        return PromptTemplates.canonicalPromptKey(key);
    }
}