package com.teknolojikpanda.bitbucket.aireviewer.util;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Utility methods for extracting chunk telemetry entries from metrics payloads.
 */
public final class ChunkTelemetryUtil {

    private static final Logger log = LoggerFactory.getLogger(ChunkTelemetryUtil.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<Map<String, Object>>() {};
    private static final int PAYLOAD_PREVIEW_LIMIT = 80;
    private static volatile Consumer<String> warningObserver;

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
        Map<String, Object> map = readMetricsMap(metricsJson);
        if (map.isEmpty()) {
            return Collections.emptyList();
        }
        return extractEntries(map);
    }

    @Nonnull
    public static Map<String, Object> readMetricsMap(String metricsJson) {
        return readMetricsMapResult(metricsJson).getMetrics();
    }

    @Nonnull
    public static MetricsParseResult readMetricsMapResult(String metricsJson) {
        String payload = LargeFieldCompression.decompress(metricsJson);
        if (payload == null || payload.trim().isEmpty()) {
            return MetricsParseResult.success(Collections.emptyMap());
        }
        try {
            Map<String, Object> parsed = OBJECT_MAPPER.readValue(payload, MAP_TYPE);
            return MetricsParseResult.success(parsed != null ? parsed : Collections.emptyMap());
        } catch (Exception ex) {
            String warning = String.format(
                    "Failed to parse chunk telemetry metrics payload. payloadLength=%d, payloadPreview='%s'",
                    payload.length(), boundedPreview(payload));
            log.warn(warning, ex);
            Consumer<String> observer = warningObserver;
            if (observer != null) {
                observer.accept(warning);
            }
            return MetricsParseResult.failed();
        }
    }

    static void setWarningObserverForTests(Consumer<String> observer) {
        warningObserver = observer;
    }

    static void clearWarningObserverForTests() {
        warningObserver = null;
    }

    private static String boundedPreview(String payload) {
        String compact = payload.replaceAll("\\s+", " ").trim();
        if (compact.length() <= PAYLOAD_PREVIEW_LIMIT) {
            return compact;
        }
        return compact.substring(0, PAYLOAD_PREVIEW_LIMIT) + "...";
    }

    public static final class MetricsParseResult {
        private final Map<String, Object> metrics;
        private final boolean parseFailed;

        private MetricsParseResult(Map<String, Object> metrics, boolean parseFailed) {
            this.metrics = metrics;
            this.parseFailed = parseFailed;
        }

        public static MetricsParseResult success(Map<String, Object> metrics) {
            return new MetricsParseResult(metrics, false);
        }

        public static MetricsParseResult failed() {
            return new MetricsParseResult(Collections.emptyMap(), true);
        }

        @Nonnull
        public Map<String, Object> getMetrics() {
            return metrics;
        }

        public boolean isParseFailed() {
            return parseFailed;
        }
    }
}
