package com.teknolojikpanda.bitbucket.aireviewer.service;

import com.atlassian.scheduler.JobRunner;
import com.atlassian.scheduler.JobRunnerResponse;
import com.atlassian.scheduler.SchedulerService;
import com.atlassian.scheduler.SchedulerServiceException;
import com.atlassian.scheduler.config.JobConfig;
import com.atlassian.scheduler.status.RunOutcome;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class ReviewHistoryCleanupSchedulerTest {

    private SchedulerService schedulerService;
    private ReviewHistoryCleanupService cleanupService;
    private ReviewHistoryCleanupStatusService statusService;
    private ReviewHistoryCleanupAuditService auditService;

    @Before
    public void setUp() {
        schedulerService = mock(SchedulerService.class);
        cleanupService = mock(ReviewHistoryCleanupService.class);
        statusService = mock(ReviewHistoryCleanupStatusService.class);
        auditService = mock(ReviewHistoryCleanupAuditService.class);
    }

    @Test
    public void schedulesJobWhenEnabled() throws SchedulerServiceException {
        when(statusService.getStatus()).thenReturn(
                ReviewHistoryCleanupStatusService.Status.snapshot(true, 90, 200, 1440, 0L, 0L, 0, 0, null));

        ReviewHistoryCleanupScheduler scheduler = new ReviewHistoryCleanupScheduler(schedulerService, cleanupService, statusService, auditService);
        scheduler.onStart();

        verify(schedulerService).unregisterJobRunner(eq(ReviewHistoryCleanupScheduler.JOB_RUNNER_KEY));
        verify(schedulerService).registerJobRunner(eq(ReviewHistoryCleanupScheduler.JOB_RUNNER_KEY), any(JobRunner.class));
        verify(schedulerService).scheduleJob(eq(ReviewHistoryCleanupScheduler.JOB_ID), any(JobConfig.class));
    }

    @Test
    public void skipsSchedulingWhenDisabled() throws SchedulerServiceException {
        when(statusService.getStatus()).thenReturn(
                ReviewHistoryCleanupStatusService.Status.snapshot(false, 90, 200, 1440, 0L, 0L, 0, 0, null));

        ReviewHistoryCleanupScheduler scheduler = new ReviewHistoryCleanupScheduler(schedulerService, cleanupService, statusService, auditService);
        scheduler.onStart();

        verify(schedulerService).unregisterJobRunner(eq(ReviewHistoryCleanupScheduler.JOB_RUNNER_KEY));
        verify(schedulerService).registerJobRunner(eq(ReviewHistoryCleanupScheduler.JOB_RUNNER_KEY), any(JobRunner.class));
        verify(schedulerService, never()).scheduleJob(eq(ReviewHistoryCleanupScheduler.JOB_ID), any(JobConfig.class));
    }

    @Test
    public void rescheduleUnschedulesExistingJob() throws SchedulerServiceException {
        ReviewHistoryCleanupStatusService.Status status =
                ReviewHistoryCleanupStatusService.Status.snapshot(true, 90, 200, 1440, 0L, 0L, 0, 0, null);
        when(statusService.getStatus()).thenReturn(status, status);

        ReviewHistoryCleanupScheduler scheduler = new ReviewHistoryCleanupScheduler(schedulerService, cleanupService, statusService, auditService);
        scheduler.onStart();
        scheduler.reschedule();

        verify(schedulerService).unscheduleJob(ReviewHistoryCleanupScheduler.JOB_ID);
        verify(schedulerService, times(2)).scheduleJob(eq(ReviewHistoryCleanupScheduler.JOB_ID), any(JobConfig.class));
    }

    @Test
    public void jobRunsCleanupAndRecordsStatus() {
        ReviewHistoryCleanupStatusService.Status status =
                ReviewHistoryCleanupStatusService.Status.snapshot(true, 45, 125, 60, 0L, 0L, 0, 0, null);
        when(statusService.getStatus()).thenReturn(status);
        ReviewHistoryCleanupService.CleanupResult result =
                new ReviewHistoryCleanupService.CleanupResult(45, 125, 5, 18, 42, System.currentTimeMillis(), 800L, 4.5);
        when(cleanupService.cleanupOlderThanDays(45, 125)).thenReturn(result);

        ReviewHistoryCleanupScheduler scheduler = new ReviewHistoryCleanupScheduler(schedulerService, cleanupService, statusService, auditService);
        scheduler.onStart();
        JobRunner runner = captureRunner();

        JobRunnerResponse response = runner.runJob(null);

        verify(cleanupService).cleanupOlderThanDays(45, 125);
        verify(statusService).recordRun(eq(result));
        verify(auditService).recordRun(anyLong(), anyLong(), eq(5), eq(18), eq(false), eq("system"), eq("System"));
        assertNotNull(response.getMessage());
        assertEquals(RunOutcome.SUCCESS, response.getRunOutcome());
    }

    @Test
    public void jobSkipsWhenDisabled() {
        ReviewHistoryCleanupStatusService.Status status =
                ReviewHistoryCleanupStatusService.Status.snapshot(false, 45, 125, 60, 0L, 0L, 0, 0, null);
        when(statusService.getStatus()).thenReturn(status);

        ReviewHistoryCleanupScheduler scheduler = new ReviewHistoryCleanupScheduler(schedulerService, cleanupService, statusService, auditService);
        scheduler.onStart();
        JobRunner runner = captureRunner();

        JobRunnerResponse response = runner.runJob(null);

        verify(cleanupService, never()).cleanupOlderThanDays(anyInt(), anyInt());
        verify(statusService, never()).recordRun(any());
        assertEquals(RunOutcome.ABORTED, response.getRunOutcome());
    }

    @Test
    public void jobRecordsFailureOnException() {
        ReviewHistoryCleanupStatusService.Status status =
                ReviewHistoryCleanupStatusService.Status.snapshot(true, 30, 50, 120, 0L, 0L, 0, 0, null);
        when(statusService.getStatus()).thenReturn(status);
        when(cleanupService.cleanupOlderThanDays(30, 50)).thenThrow(new RuntimeException("boom"));

        ReviewHistoryCleanupScheduler scheduler = new ReviewHistoryCleanupScheduler(schedulerService, cleanupService, statusService, auditService);
        scheduler.onStart();
        JobRunner runner = captureRunner();

        JobRunnerResponse response = runner.runJob(null);

        verify(statusService).recordFailure("boom");
        verify(auditService).recordFailure(anyLong(), eq(false), eq("system"), eq("System"), eq("boom"));
        assertEquals(RunOutcome.FAILED, response.getRunOutcome());
    }

    private JobRunner captureRunner() {
        ArgumentCaptor<JobRunner> captor = ArgumentCaptor.forClass(JobRunner.class);
        verify(schedulerService).registerJobRunner(eq(ReviewHistoryCleanupScheduler.JOB_RUNNER_KEY), captor.capture());
        return captor.getValue();
    }
}
