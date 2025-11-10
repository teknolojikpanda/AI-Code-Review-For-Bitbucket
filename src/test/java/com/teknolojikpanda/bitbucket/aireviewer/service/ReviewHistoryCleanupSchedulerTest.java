package com.teknolojikpanda.bitbucket.aireviewer.service;

import com.atlassian.sal.api.scheduling.PluginScheduler;
import org.junit.Before;
import org.junit.Test;

import java.util.Date;
import java.util.HashMap;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class ReviewHistoryCleanupSchedulerTest {

    private PluginScheduler pluginScheduler;
    private ReviewHistoryCleanupService cleanupService;
    private ReviewHistoryCleanupStatusService statusService;

    @Before
    public void setUp() {
        pluginScheduler = mock(PluginScheduler.class);
        cleanupService = mock(ReviewHistoryCleanupService.class);
        statusService = mock(ReviewHistoryCleanupStatusService.class);
    }

    @Test
    public void schedulesJobWhenEnabled() {
        when(statusService.getStatus()).thenReturn(
                ReviewHistoryCleanupStatusService.Status.snapshot(true, 90, 200, 1440, 0L, 0L, 0, 0, null));

        new ReviewHistoryCleanupScheduler(pluginScheduler, cleanupService, statusService);

        verify(pluginScheduler).scheduleJob(
                eq("ai-review-history-cleanup"),
                eq(ReviewHistoryCleanupScheduler.ReviewHistoryCleanupJob.class),
                anyMap(),
                any(Date.class),
                anyLong());
    }

    @Test
    public void skipsSchedulingWhenDisabled() {
        when(statusService.getStatus()).thenReturn(
                ReviewHistoryCleanupStatusService.Status.snapshot(false, 90, 200, 1440, 0L, 0L, 0, 0, null));

        new ReviewHistoryCleanupScheduler(pluginScheduler, cleanupService, statusService);

        verify(pluginScheduler, never()).scheduleJob(anyString(), any(), anyMap(), any(Date.class), anyLong());
    }

    @Test
    public void rescheduleUnschedulesExistingJob() {
        ReviewHistoryCleanupStatusService.Status status =
                ReviewHistoryCleanupStatusService.Status.snapshot(true, 90, 200, 1440, 0L, 0L, 0, 0, null);
        when(statusService.getStatus()).thenReturn(status, status);

        ReviewHistoryCleanupScheduler scheduler = new ReviewHistoryCleanupScheduler(pluginScheduler, cleanupService, statusService);
        scheduler.reschedule();

        verify(pluginScheduler).unscheduleJob("ai-review-history-cleanup");
        verify(pluginScheduler, times(2)).scheduleJob(
                eq("ai-review-history-cleanup"),
                eq(ReviewHistoryCleanupScheduler.ReviewHistoryCleanupJob.class),
                anyMap(),
                any(Date.class),
                anyLong());
    }

    @Test
    public void jobRunsCleanupAndRecordsStatus() {
        ReviewHistoryCleanupScheduler.ReviewHistoryCleanupJob job =
                new ReviewHistoryCleanupScheduler.ReviewHistoryCleanupJob();
        Map<String, Object> data = new HashMap<>();
        data.put("cleanupService", cleanupService);
        data.put("statusService", statusService);

        ReviewHistoryCleanupStatusService.Status status =
                ReviewHistoryCleanupStatusService.Status.snapshot(true, 45, 125, 60, 0L, 0L, 0, 0, null);
        when(statusService.getStatus()).thenReturn(status);
        ReviewHistoryCleanupService.CleanupResult result =
                new ReviewHistoryCleanupService.CleanupResult(45, 125, 5, 18, 42, System.currentTimeMillis());
        when(cleanupService.cleanupOlderThanDays(45, 125)).thenReturn(result);

        job.execute(data);

        verify(cleanupService).cleanupOlderThanDays(45, 125);
        verify(statusService).recordRun(eq(result), anyLong());
    }

    @Test
    public void jobSkipsWhenDisabled() {
        ReviewHistoryCleanupScheduler.ReviewHistoryCleanupJob job =
                new ReviewHistoryCleanupScheduler.ReviewHistoryCleanupJob();
        Map<String, Object> data = new HashMap<>();
        data.put("cleanupService", cleanupService);
        data.put("statusService", statusService);
        when(statusService.getStatus()).thenReturn(
                ReviewHistoryCleanupStatusService.Status.snapshot(false, 45, 125, 60, 0L, 0L, 0, 0, null));

        job.execute(data);

        verify(cleanupService, never()).cleanupOlderThanDays(anyInt(), anyInt());
        verify(statusService, never()).recordRun(any(), anyLong());
    }

    @Test
    public void jobRecordsFailureOnException() {
        ReviewHistoryCleanupScheduler.ReviewHistoryCleanupJob job =
                new ReviewHistoryCleanupScheduler.ReviewHistoryCleanupJob();
        Map<String, Object> data = new HashMap<>();
        data.put("cleanupService", cleanupService);
        data.put("statusService", statusService);
        ReviewHistoryCleanupStatusService.Status status =
                ReviewHistoryCleanupStatusService.Status.snapshot(true, 30, 50, 120, 0L, 0L, 0, 0, null);
        when(statusService.getStatus()).thenReturn(status);
        when(cleanupService.cleanupOlderThanDays(30, 50)).thenThrow(new RuntimeException("boom"));

        job.execute(data);

        verify(statusService).recordFailure("boom");
    }
}
