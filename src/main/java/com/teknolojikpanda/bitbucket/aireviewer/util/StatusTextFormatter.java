package com.teknolojikpanda.bitbucket.aireviewer.util;

public final class StatusTextFormatter {

    private StatusTextFormatter() {
    }

    public static String humanize(String value, String defaultValue, boolean replaceDot) {
        if (value == null || value.trim().isEmpty()) {
            return defaultValue;
        }

        String normalized = value;
        if (replaceDot) {
            normalized = normalized.replace('.', ' ');
        }
        normalized = normalized.replace('_', ' ').replace('-', ' ').trim();

        if (normalized.isEmpty()) {
            return defaultValue;
        }

        String[] tokens = normalized.split("\\s+");
        StringBuilder builder = new StringBuilder();
        for (String token : tokens) {
            if (token == null || token.isEmpty()) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append(' ');
            }
            builder.append(Character.toUpperCase(token.charAt(0)));
            if (token.length() > 1) {
                builder.append(token.substring(1).toLowerCase());
            }
        }

        return builder.length() == 0 ? defaultValue : builder.toString();
    }
}