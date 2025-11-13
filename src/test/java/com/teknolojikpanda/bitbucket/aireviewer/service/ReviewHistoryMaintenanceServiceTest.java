package com.teknolojikpanda.bitbucket.aireviewer.service;

import org.junit.Before;
import org.junit.Test;

import java.time.ZoneId;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

public class ReviewHistoryMaintenanceServiceTest {

    private ReviewHistoryCleanupService cleanupService;
    private ReviewHistoryMaintenanceService maintenanceService;

    @Before
    public void setUp() {
        cleanupService = mock(ReviewHistoryCleanupService.class);
        maintenanceService = new ReviewHistoryMaintenanceService(cleanupService, ZoneId.of("UTC"));
    }

    @Test
    public void runMaintenanceHonorsMaxBatchesAndAggregatesCounts() {
        ReviewHistoryCleanupStatusService.Status status =
                ReviewHistoryCleanupStatusService.Status.snapshot(true, 30, 5, 1440, 2, 120, 2,
                        0L, 0L, 0, 0, 0, null);
        ReviewHistoryCleanupService.CleanupResult batch =
                new ReviewHistoryCleanupService.CleanupResult(30, 5, 5, 10, 100, System.currentTimeMillis(), 50L, 2.0);
        when(cleanupService.cleanupOlderThanDays(anyInt(), anyInt())).thenReturn(batch);

        ReviewHistoryMaintenanceService.MaintenanceRun run = maintenanceService.runMaintenanceWindow(status);

        verify(cleanupService, times(2)).cleanupOlderThanDays(30, 5);
        assertEquals(10, run.getDeletedHistories());
        assertEquals(20, run.getDeletedChunks());
        assertEquals(2, run.getBatchesExecuted());
        assertEquals(2, run.getAggregatedResult().getBatchesExecuted());
    }

    @Test
    public void windowDetectionMatchesConfiguredHour() {
        ReviewHistoryCleanupStatusService.Status status =
                ReviewHistoryCleanupStatusService.Status.snapshot(true, 30, 5, 1440, 1, 60, 3,
                        0L, 0L, 0, 0, 0, null);
        long withinWindow = java.time.ZonedDateTime.of(2025, 1, 1, 1, 30, 0, 0, ZoneId.of("UTC"))
                .toInstant().toEpochMilli();
        long outsideWindow = java.time.ZonedDateTime.of(2025, 1, 1, 5, 0, 0, 0, ZoneId.of("UTC"))
                .toInstant().toEpochMilli();

        assertTrue(maintenanceService.isWithinWindow(status, withinWindow));
        assertFalse(maintenanceService.isWithinWindow(status, outsideWindow));
        long nextStart = maintenanceService.nextWindowStartMillis(status, outsideWindow);
        long expectedStart = java.time.ZonedDateTime.of(2025, 1, 2, 1, 0, 0, 0, ZoneId.of("UTC"))
                .toInstant().toEpochMilli();
        assertEquals(expectedStart, nextStart);
    }
}
