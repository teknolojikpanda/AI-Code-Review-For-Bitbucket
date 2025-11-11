package com.teknolojikpanda.bitbucket.aireviewer.service;

import com.atlassian.activeobjects.external.ActiveObjects;
import com.teknolojikpanda.bitbucket.aireviewer.util.ChunkTelemetryUtil;
import org.junit.Before;
import org.junit.Test;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.mockito.Mockito.mock;

public class ReviewHistoryServiceTest {

    private ActiveObjects ao;
    private ReviewHistoryService service;

    @Before
    public void setUp() {
        ao = mock(ActiveObjects.class);
        service = new ReviewHistoryService(ao);
    }

    @Test
    public void extractEntriesFromJsonHandlesSamplePayload() {
        String json = "{\"ai.chunk.invocations\":[{\"chunkId\":\"chunk-1\",\"attempts\":1}]}";
        List<Map<String, Object>> entries = ChunkTelemetryUtil.extractEntriesFromJson(json);
        assertEquals(1, entries.size());
        assertEquals("chunk-1", entries.get(0).get("chunkId"));
    }

    @Test
    public void safeLongToIntClampsValues() {
        assertEquals(Integer.MAX_VALUE, ReviewHistoryService.safeLongToInt(Integer.MAX_VALUE + 10L));
        assertEquals(Integer.MIN_VALUE, ReviewHistoryService.safeLongToInt(Integer.MIN_VALUE - 10L));
        assertEquals(42, ReviewHistoryService.safeLongToInt(42));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void guardrailsTelemetryIncludesLimiterData() {
        Map<String, Object> metrics = new LinkedHashMap<>();
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("scope", "repository");
        snapshot.put("identifier", "PRJ/repo");
        snapshot.put("limitPerHour", 30);
        snapshot.put("remaining", 2);
        metrics.put("rate.snapshot", snapshot);

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("scope", "repository");
        details.put("identifier", "PRJ/repo");
        details.put("retryAfterMs", 60000L);
        details.put("limiterSnapshot", Collections.singletonMap("remaining", 0));

        Map<String, Object> event = new LinkedHashMap<>();
        event.put("stage", "review.throttled");
        event.put("timestamp", 1700000000000L);
        event.put("details", details);

        Map<String, Object> guardrails = service.buildGuardrailsTelemetry(Collections.singletonList(event), metrics);
        Map<String, Object> limiter = (Map<String, Object>) guardrails.get("limiter");
        assertNotNull(limiter);
        assertEquals("repository", limiter.get("scope"));
        List<Map<String, Object>> incidents = (List<Map<String, Object>>) limiter.get("incidents");
        assertEquals(1, incidents.size());
        assertEquals(60000L, incidents.get(0).get("retryAfterMs"));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void guardrailsTelemetryIncludesCircuitSamples() {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("circuitState", "OPEN");
        details.put("circuitSnapshot", Collections.singletonMap("blockedCalls", 3));

        Map<String, Object> event = new LinkedHashMap<>();
        event.put("stage", "analysis.started");
        event.put("timestamp", 1700001000000L);
        event.put("details", details);

        Map<String, Object> guardrails = service.buildGuardrailsTelemetry(Collections.singletonList(event), Collections.emptyMap());
        Map<String, Object> circuit = (Map<String, Object>) guardrails.get("circuit");
        assertNotNull(circuit);
        List<Map<String, Object>> samples = (List<Map<String, Object>>) circuit.get("samples");
        assertEquals(1, samples.size());
        Map<String, Object> sample = samples.get(0);
        assertEquals("OPEN", sample.get("state"));
        Map<String, Object> snapshot = (Map<String, Object>) sample.get("snapshot");
        assertEquals(3L, snapshot.get("blockedCalls"));
    }
}
