package com.teknolojikpanda.bitbucket.aireviewer.service;

import com.atlassian.plugin.spring.scanner.annotation.imports.ComponentImport;
import com.atlassian.sal.api.scheduling.PluginJob;
import com.atlassian.sal.api.scheduling.PluginScheduler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.inject.Named;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

@Named
public class ReviewHistoryCleanupScheduler {

    private static final Logger log = LoggerFactory.getLogger(ReviewHistoryCleanupScheduler.class);
    private static final String JOB_NAME = "ai-review-history-cleanup";

    private final PluginScheduler scheduler;
    private final ReviewHistoryCleanupService cleanupService;
    private final ReviewHistoryCleanupStatusService statusService;

    @Inject
    public ReviewHistoryCleanupScheduler(@ComponentImport PluginScheduler scheduler,
                                         ReviewHistoryCleanupService cleanupService,
                                         ReviewHistoryCleanupStatusService statusService) {
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.cleanupService = Objects.requireNonNull(cleanupService, "cleanupService");
        this.statusService = Objects.requireNonNull(statusService, "statusService");
        scheduleNextRun();
    }

    public void reschedule() {
        scheduler.unscheduleJob(JOB_NAME);
        scheduleNextRun();
    }

    private void scheduleNextRun() {
        ReviewHistoryCleanupStatusService.Status status = statusService.getStatus();
        if (!status.isEnabled()) {
            log.info("AI review history cleanup job disabled; not scheduling");
            return;
        }
        long intervalMs = TimeUnit.MINUTES.toMillis(Math.max(5, status.getIntervalMinutes()));
        Map<String, Object> params = new HashMap<>();
        params.put("cleanupService", cleanupService);
        params.put("statusService", statusService);
        scheduler.scheduleJob(JOB_NAME,
                ReviewHistoryCleanupJob.class,
                params,
                new Date(System.currentTimeMillis() + intervalMs),
                intervalMs);
        log.info("Scheduled AI review history cleanup job every {} minutes", status.getIntervalMinutes());
    }

    public static class ReviewHistoryCleanupJob implements PluginJob {
        @Override
        public void execute(Map<String, Object> jobDataMap) {
            ReviewHistoryCleanupService cleanupService = (ReviewHistoryCleanupService) jobDataMap.get("cleanupService");
            ReviewHistoryCleanupStatusService statusService = (ReviewHistoryCleanupStatusService) jobDataMap.get("statusService");
            ReviewHistoryCleanupStatusService.Status status = statusService.getStatus();
            if (!status.isEnabled()) {
                return;
            }
            long start = System.currentTimeMillis();
            try {
                ReviewHistoryCleanupService.CleanupResult result = cleanupService.cleanupOlderThanDays(status.getRetentionDays(), status.getBatchSize());
                long duration = System.currentTimeMillis() - start;
                statusService.recordRun(result, duration);
            } catch (Exception ex) {
                statusService.recordFailure(ex.getMessage());
                log.warn("AI review history cleanup job failed: {}", ex.getMessage(), ex);
            }
        }
    }
}
