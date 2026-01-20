package com.teknolojikpanda.bitbucket.aireviewer.util;

import java.util.Locale;

public final class PromptKeySupport {

    private PromptKeySupport() {
    }

    public static boolean isPromptKey(String key) {
        if (key == null) {
            return false;
        }
        return key.trim().toLowerCase(Locale.ROOT).startsWith("prompt");
    }
}