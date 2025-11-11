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
 * Periodically publishes the local worker pool snapshot so the admin dashboard can display
 * per-node statistics without requiring manual refreshes on every node.
 */
@Named
@Singleton
public class GuardrailsWorkerHeartbeatScheduler implements LifecycleAware, DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(GuardrailsWorkerHeartbeatScheduler.class);
    private static final JobRunnerKey JOB_RUNNER_KEY =
            JobRunnerKey.of("com.teknolojikpanda.bitbucket.ai-code-reviewer:worker-heartbeat-runner");
    private static final JobId JOB_ID =
            JobId.of("com.teknolojikpanda.bitbucket.ai-code-reviewer:worker-heartbeat-job");
    private static final long HEARTBEAT_INTERVAL_MS = TimeUnit.SECONDS.toMillis(30);

    private final SchedulerService schedulerService;
    private final ReviewWorkerPool workerPool;
    private final GuardrailsWorkerNodeService nodeService;
    private final JobRunner runner = new HeartbeatRunner();
    private volatile boolean lifecycleStarted;

    @Inject
    public GuardrailsWorkerHeartbeatScheduler(@ComponentImport SchedulerService schedulerService,
                                              ReviewWorkerPool workerPool,
                                              GuardrailsWorkerNodeService nodeService) {
        this.schedulerService = Objects.requireNonNull(schedulerService, "schedulerService");
        this.workerPool = Objects.requireNonNull(workerPool, "workerPool");
        this.nodeService = Objects.requireNonNull(nodeService, "nodeService");
    }

    @Override
    public void onStart() {
        schedulerService.unregisterJobRunner(JOB_RUNNER_KEY);
        schedulerService.registerJobRunner(JOB_RUNNER_KEY, runner);
        lifecycleStarted = true;
        scheduleHeartbeat();
    }

    @Override
    public void onStop() {
        shutdown();
    }

    @Override
    public void destroy() {
        shutdown();
    }

    private void scheduleHeartbeat() {
        if (!lifecycleStarted) {
            return;
        }
        schedulerService.unscheduleJob(JOB_ID);
        JobConfig jobConfig = JobConfig.forJobRunnerKey(JOB_RUNNER_KEY)
                .withRunMode(RunMode.RUN_LOCALLY)
                .withSchedule(Schedule.forInterval(
                        HEARTBEAT_INTERVAL_MS,
                        new Date(System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(5))));
        try {
            schedulerService.scheduleJob(JOB_ID, jobConfig);
            log.debug("Scheduled worker heartbeat to run every {} seconds", HEARTBEAT_INTERVAL_MS / 1000);
        } catch (SchedulerServiceException ex) {
            log.warn("Failed to schedule worker heartbeat job", ex);
        }
    }

    private void shutdown() {
        lifecycleStarted = false;
        schedulerService.unscheduleJob(JOB_ID);
        schedulerService.unregisterJobRunner(JOB_RUNNER_KEY);
    }

    private class HeartbeatRunner implements JobRunner {
        @Override
        public JobRunnerResponse runJob(JobRunnerRequest request) {
            try {
                ReviewWorkerPool.WorkerPoolSnapshot snapshot = workerPool.snapshot();
                nodeService.recordLocalSnapshot(snapshot);
                return JobRunnerResponse.success("Recorded worker snapshot");
            } catch (Exception ex) {
                log.debug("Worker heartbeat failed: {}", ex.getMessage(), ex);
                return JobRunnerResponse.failed(ex);
            }
        }
    }
}
