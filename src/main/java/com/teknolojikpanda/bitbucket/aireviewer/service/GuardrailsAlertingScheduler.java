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
import javax.inject.Singleton;
import java.util.Date;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * Periodically evaluates guardrail alerts and pushes them to configured channels.
 */
@Named
@Singleton
public class GuardrailsAlertingScheduler implements LifecycleAware, DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(GuardrailsAlertingScheduler.class);
    private static final JobRunnerKey JOB_RUNNER_KEY =
            JobRunnerKey.of("com.teknolojikpanda.bitbucket.ai-code-reviewer:alerting-runner");
    private static final JobId JOB_ID =
            JobId.of("com.teknolojikpanda.bitbucket.ai-code-reviewer:alerting-job");
    private static final long ALERT_INTERVAL_MS = TimeUnit.MINUTES.toMillis(1);

    private final SchedulerService schedulerService;
    private final GuardrailsAlertingService alertingService;
    private final JobRunner runner = new AlertingRunner();
    private volatile boolean lifecycleStarted;

    @Inject
    public GuardrailsAlertingScheduler(@ComponentImport SchedulerService schedulerService,
                                       GuardrailsAlertingService alertingService) {
        this.schedulerService = Objects.requireNonNull(schedulerService, "schedulerService");
        this.alertingService = Objects.requireNonNull(alertingService, "alertingService");
    }

    @Override
    public void onStart() {
        schedulerService.unregisterJobRunner(JOB_RUNNER_KEY);
        schedulerService.registerJobRunner(JOB_RUNNER_KEY, runner);
        lifecycleStarted = true;
        scheduleAlerting();
    }

    @Override
    public void onStop() {
        shutdown();
    }

    @Override
    public void destroy() {
        shutdown();
    }

    private void scheduleAlerting() {
        if (!lifecycleStarted) {
            return;
        }
        schedulerService.unscheduleJob(JOB_ID);
        JobConfig config = JobConfig.forJobRunnerKey(JOB_RUNNER_KEY)
                .withRunMode(RunMode.RUN_ONCE_PER_CLUSTER)
                .withSchedule(Schedule.forInterval(
                        ALERT_INTERVAL_MS,
                        new Date(System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(15))));
        try {
            schedulerService.scheduleJob(JOB_ID, config);
            log.debug("Scheduled guardrails alerting job every {} seconds", ALERT_INTERVAL_MS / 1000);
        } catch (SchedulerServiceException ex) {
            log.warn("Failed to schedule guardrails alerting job", ex);
        }
    }

    private void shutdown() {
        lifecycleStarted = false;
        schedulerService.unscheduleJob(JOB_ID);
        schedulerService.unregisterJobRunner(JOB_RUNNER_KEY);
    }

    private class AlertingRunner implements JobRunner {
        @Override
        public JobRunnerResponse runJob(JobRunnerRequest request) {
            try {
                GuardrailsAlertingService.AlertSnapshot snapshot = alertingService.evaluateAndNotify();
                if (snapshot.getAlerts().isEmpty()) {
                    return JobRunnerResponse.success("No guardrails alerts to dispatch");
                }
                return JobRunnerResponse.success("Dispatched " + snapshot.getAlerts().size() + " guardrails alerts");
            } catch (Exception ex) {
                log.warn("Guardrails alerting job failed: {}", ex.getMessage(), ex);
                return JobRunnerResponse.failed(ex);
            }
        }
    }
}
