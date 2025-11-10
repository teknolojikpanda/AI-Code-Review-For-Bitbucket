package com.teknolojikpanda.bitbucket.aireviewer.service;

import com.atlassian.plugin.spring.scanner.annotation.imports.ComponentImport;
import com.atlassian.scheduler.JobRunner;
import com.atlassian.scheduler.JobRunnerRequest;
import com.atlassian.scheduler.JobRunnerResponse;
import com.atlassian.scheduler.SchedulerService;
import com.atlassian.scheduler.SchedulerServiceException;
import com.atlassian.scheduler.config.JobConfig;
import com.atlassian.scheduler.config.JobId;
import com.atlassian.scheduler.config.JobRunnerKey;
import com.atlassian.scheduler.config.RunMode;
import com.atlassian.scheduler.config.Schedule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;

import javax.inject.Inject;
import javax.inject.Named;
import java.util.Date;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

@Named
public class ReviewHistoryCleanupScheduler implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(ReviewHistoryCleanupScheduler.class);
    static final JobRunnerKey JOB_RUNNER_KEY = JobRunnerKey.of("com.teknolojikpanda.bitbucket.ai-code-reviewer:history-cleanup-runner");
    static final JobId JOB_ID = JobId.of("com.teknolojikpanda.bitbucket.ai-code-reviewer:history-cleanup-job");

    private final SchedulerService schedulerService;
    private final ReviewHistoryCleanupService cleanupService;
    private final ReviewHistoryCleanupStatusService statusService;
    private final JobRunner cleanupRunner = new CleanupJobRunner();

    @Inject
    public ReviewHistoryCleanupScheduler(@ComponentImport SchedulerService schedulerService,
                                         ReviewHistoryCleanupService cleanupService,
                                         ReviewHistoryCleanupStatusService statusService) {
        this.schedulerService = Objects.requireNonNull(schedulerService, "schedulerService");
        this.cleanupService = Objects.requireNonNull(cleanupService, "cleanupService");
        this.statusService = Objects.requireNonNull(statusService, "statusService");
        registerJobRunner();
        scheduleNextRun();
    }

    public void reschedule() {
        schedulerService.unscheduleJob(JOB_ID);
        scheduleNextRun();
    }

    @Override
    public void destroy() {
        schedulerService.unscheduleJob(JOB_ID);
        schedulerService.unregisterJobRunner(JOB_RUNNER_KEY);
    }

    private void registerJobRunner() {
        schedulerService.unregisterJobRunner(JOB_RUNNER_KEY);
        schedulerService.registerJobRunner(JOB_RUNNER_KEY, cleanupRunner);
    }

    private void scheduleNextRun() {
        ReviewHistoryCleanupStatusService.Status status = statusService.getStatus();
        if (!status.isEnabled()) {
            log.info("AI review history cleanup job disabled; not scheduling");
            return;
        }
        long intervalMs = TimeUnit.MINUTES.toMillis(Math.max(5, status.getIntervalMinutes()));
        Date firstRun = new Date(System.currentTimeMillis() + intervalMs);
        JobConfig jobConfig = JobConfig.forJobRunnerKey(JOB_RUNNER_KEY)
                .withRunMode(RunMode.RUN_ONCE_PER_CLUSTER)
                .withSchedule(Schedule.forInterval(intervalMs, firstRun));
        try {
            schedulerService.scheduleJob(JOB_ID, jobConfig);
            log.info("Scheduled AI review history cleanup job every {} minutes", status.getIntervalMinutes());
        } catch (SchedulerServiceException e) {
            log.error("Failed to schedule AI review history cleanup job", e);
        }
    }

    private class CleanupJobRunner implements JobRunner {
        @Override
        public JobRunnerResponse runJob(JobRunnerRequest request) {
            ReviewHistoryCleanupStatusService.Status status = statusService.getStatus();
            if (!status.isEnabled()) {
                return JobRunnerResponse.aborted("Cleanup disabled");
            }
            long start = System.currentTimeMillis();
            try {
                ReviewHistoryCleanupService.CleanupResult result = cleanupService.cleanupOlderThanDays(status.getRetentionDays(), status.getBatchSize());
                long duration = System.currentTimeMillis() - start;
                statusService.recordRun(result, duration);
                return JobRunnerResponse.success("Deleted " + result.getDeletedHistories() + " histories");
            } catch (Exception ex) {
                statusService.recordFailure(ex.getMessage());
                log.warn("AI review history cleanup job failed: {}", ex.getMessage(), ex);
                return JobRunnerResponse.failed(ex);
            }
        }
    }
}
