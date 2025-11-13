package com.teknolojikpanda.bitbucket.aireviewer.service;

import com.atlassian.plugin.spring.scanner.annotation.imports.ComponentImport;
import com.atlassian.sal.api.lifecycle.LifecycleAware;
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
public class ReviewHistoryCleanupScheduler implements LifecycleAware, DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(ReviewHistoryCleanupScheduler.class);
    static final JobRunnerKey JOB_RUNNER_KEY = JobRunnerKey.of("com.teknolojikpanda.bitbucket.ai-code-reviewer:history-cleanup-runner");
    static final JobId JOB_ID = JobId.of("com.teknolojikpanda.bitbucket.ai-code-reviewer:history-cleanup-job");

    private final SchedulerService schedulerService;
    private final ReviewHistoryCleanupService cleanupService;
    private final ReviewHistoryMaintenanceService maintenanceService;
    private final ReviewHistoryCleanupStatusService statusService;
    private final ReviewHistoryCleanupAuditService auditService;
    private final JobRunner cleanupRunner = new CleanupJobRunner();
    private volatile boolean lifecycleStarted;

    @Inject
    public ReviewHistoryCleanupScheduler(@ComponentImport SchedulerService schedulerService,
                                         ReviewHistoryCleanupService cleanupService,
                                         ReviewHistoryMaintenanceService maintenanceService,
                                         ReviewHistoryCleanupStatusService statusService,
                                         ReviewHistoryCleanupAuditService auditService) {
        this.schedulerService = Objects.requireNonNull(schedulerService, "schedulerService");
        this.cleanupService = Objects.requireNonNull(cleanupService, "cleanupService");
        this.maintenanceService = Objects.requireNonNull(maintenanceService, "maintenanceService");
        this.statusService = Objects.requireNonNull(statusService, "statusService");
        this.auditService = Objects.requireNonNull(auditService, "auditService");
    }

    public void reschedule() {
        if (!lifecycleStarted) {
            log.debug("Cleanup scheduler reschedule ignored; lifecycle not started");
            return;
        }
        schedulerService.unscheduleJob(JOB_ID);
        scheduleNextRun();
    }

    @Override
    public void onStart() {
        registerJobRunner();
        lifecycleStarted = true;
        scheduleNextRun();
    }

    @Override
    public void onStop() {
        shutdownScheduler();
    }

    @Override
    public void destroy() {
        shutdownScheduler();
    }

    private void registerJobRunner() {
        schedulerService.unregisterJobRunner(JOB_RUNNER_KEY);
        schedulerService.registerJobRunner(JOB_RUNNER_KEY, cleanupRunner);
    }

    private void scheduleNextRun() {
        if (!lifecycleStarted) {
            log.debug("Cleanup scheduler not started; skipping schedule");
            return;
        }
        ReviewHistoryCleanupStatusService.Status status;
        try {
            status = statusService.getStatus();
        } catch (IllegalStateException ex) {
            log.warn("ActiveObjects not ready for cleanup scheduling yet: {}", ex.getMessage());
            return;
        }
        if (!status.isEnabled()) {
            log.info("AI review history cleanup job disabled; not scheduling");
            return;
        }
        long intervalMs = TimeUnit.MINUTES.toMillis(Math.max(5, status.getIntervalMinutes()));
        long now = System.currentTimeMillis();
        long firstRunMillis = maintenanceService.computeNextScheduleTime(status, now);
        if (firstRunMillis <= now) {
            firstRunMillis = now + 2_000L;
        }
        Date firstRun = new Date(firstRunMillis);
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
            long now = System.currentTimeMillis();
            if (!maintenanceService.isWithinWindow(status, now)) {
                return JobRunnerResponse.aborted("Outside maintenance window");
            }
            long start = System.currentTimeMillis();
            try {
                ReviewHistoryMaintenanceService.MaintenanceRun run = maintenanceService.runMaintenanceWindow(status);
                ReviewHistoryCleanupService.CleanupResult result = run.getAggregatedResult();
                long duration = System.currentTimeMillis() - start;
                statusService.recordRun(result);
                auditService.recordRun(start,
                        duration,
                        run.getDeletedHistories(),
                        run.getDeletedChunks(),
                        false,
                        "system",
                        "System");
                return JobRunnerResponse.success("Deleted " + run.getDeletedHistories()
                        + " histories across " + result.getBatchesExecuted() + " batches");
            } catch (Exception ex) {
                statusService.recordFailure(ex.getMessage());
                auditService.recordFailure(start, false, "system", "System", ex.getMessage());
                log.warn("AI review history cleanup job failed: {}", ex.getMessage(), ex);
                return JobRunnerResponse.failed(ex);
            }
        }
    }

    private void shutdownScheduler() {
        lifecycleStarted = false;
        schedulerService.unscheduleJob(JOB_ID);
        schedulerService.unregisterJobRunner(JOB_RUNNER_KEY);
    }
}
