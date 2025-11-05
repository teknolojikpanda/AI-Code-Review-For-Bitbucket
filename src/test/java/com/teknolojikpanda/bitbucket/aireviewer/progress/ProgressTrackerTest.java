package com.teknolojikpanda.bitbucket.aireviewer.progress;

import org.junit.Test;

import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ProgressTrackerTest {

    @Test
    public void recordAndSnapshotPreservesOrder() {
        ProgressTracker tracker = new ProgressTracker();
        tracker.record("start", 0);
        tracker.record("middle", 50, Map.of("info", "halfway"));
        tracker.record("end", 100);

        List<ProgressEvent> events = tracker.snapshot();
        assertEquals(3, events.size());
        assertEquals("start", events.get(0).getStage());
        assertEquals(0, events.get(0).getPercentComplete());
        assertEquals("middle", events.get(1).getStage());
        assertEquals(50, events.get(1).getPercentComplete());
        assertTrue(events.get(1).getDetails().containsKey("info"));
        assertEquals("end", events.get(2).getStage());
        assertEquals(100, events.get(2).getPercentComplete());
    }

    @Test
    public void percentIsClampedBetweenZeroAndHundred() {
        ProgressTracker tracker = new ProgressTracker();
        tracker.record("negative", -10);
        tracker.record("over", 150);

        List<ProgressEvent> events = tracker.snapshot();
        assertEquals(2, events.size());
        assertEquals(0, events.get(0).getPercentComplete());
        assertEquals(100, events.get(1).getPercentComplete());
    }
}
