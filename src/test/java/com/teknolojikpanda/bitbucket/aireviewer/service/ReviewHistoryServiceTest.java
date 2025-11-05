package com.teknolojikpanda.bitbucket.aireviewer.service;

import com.atlassian.activeobjects.external.ActiveObjects;
import com.teknolojikpanda.bitbucket.aireviewer.util.ChunkTelemetryUtil;
import org.junit.Before;
import org.junit.Test;

import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
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
}
