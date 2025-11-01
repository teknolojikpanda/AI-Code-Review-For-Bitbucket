package com.example.bitbucket.aireviewer.util;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Utility methods for extracting chunk telemetry entries from metrics payloads.
 */
public final class ChunkTelemetryUtil {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<Map<String, Object>>() {};

    private ChunkTelemetryUtil() {
    }

    @Nonnull
    public static List<Map<String, Object>> extractEntries(@Nonnull Map<String, Object> metrics) {
        Object raw = metrics.get("ai.chunk.invocations");
        if (!(raw instanceof Iterable)) {
            return Collections.emptyList();
        }
        List<Map<String, Object>> entries = new ArrayList<>();
        for (Object element : (Iterable<?>) raw) {
            if (element instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> entry = new LinkedHashMap<>((Map<String, Object>) element);
                entries.add(entry);
            }
        }
        return entries;
    }

    @Nonnull
    public static List<Map<String, Object>> extractEntriesFromJson(String metricsJson) {
        if (metricsJson == null || metricsJson.trim().isEmpty()) {
            return Collections.emptyList();
        }
        try {
            Map<String, Object> map = OBJECT_MAPPER.readValue(metricsJson, MAP_TYPE);
            return extractEntries(map);
        } catch (Exception ex) {
            return Collections.emptyList();
        }
    }
}
