package com.teknolojikpanda.bitbucket.aireviewer.util;

import org.slf4j.Logger;

import javax.annotation.Nullable;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Utility helpers for emitting consistent structured log messages without requiring
 * additional logging dependencies. All messages follow the pattern
 * {@code event=<event> message="..." key=value ...} so that operations and support
 * teams can grep for specific events while still reading a human friendly summary.
 */
public final class LogSupport {

    private LogSupport() {
    }

    public static void info(Logger logger, String event, @Nullable String message, Object... fields) {
        if (logger.isInfoEnabled()) {
            logger.info(format(event, message, fields));
        }
    }

    public static void debug(Logger logger, String event, @Nullable String message, Object... fields) {
        if (logger.isDebugEnabled()) {
            logger.debug(format(event, message, fields));
        }
    }

    public static void warn(Logger logger, String event, @Nullable String message, Object... fields) {
        if (logger.isWarnEnabled()) {
            logger.warn(format(event, message, fields));
        }
    }

    public static void error(Logger logger, String event, @Nullable String message, Throwable error, Object... fields) {
        logger.error(format(event, message, fields), error);
    }

    public static void error(Logger logger, String event, @Nullable String message, Object... fields) {
        logger.error(format(event, message, fields));
    }

    private static String format(String event, @Nullable String message, Object... fields) {
        Map<String, Object> map = toFieldMap(fields);
        StringBuilder sb = new StringBuilder();
        sb.append("event=").append(event);
        if (message != null && !message.isBlank()) {
            sb.append(' ').append("message=\"").append(safe(message)).append("\"");
        }
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            Object value = entry.getValue();
            if (value == null) {
                continue;
            }
            sb.append(' ').append(entry.getKey()).append('=');
            if (value instanceof Number || value instanceof Boolean) {
                sb.append(value);
            } else {
                sb.append('"').append(safe(String.valueOf(value))).append('"');
            }
        }
        return sb.toString();
    }

    private static Map<String, Object> toFieldMap(Object... fields) {
        if (fields == null || fields.length == 0) {
            return Collections.emptyMap();
        }
        if ((fields.length & 1) == 1) {
            throw new IllegalArgumentException("Fields must be provided as key/value pairs");
        }
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < fields.length; i += 2) {
            Object key = fields[i];
            Object value = fields[i + 1];
            if (key == null) {
                continue;
            }
            String fieldKey = String.valueOf(key);
            if (fieldKey.isBlank()) {
                continue;
            }
            map.put(fieldKey, value);
        }
        return map;
    }

    private static String safe(String value) {
        return Objects.requireNonNullElse(value, "").replace('\n', ' ').replace('\r', ' ');
    }
}

