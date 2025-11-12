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
import com.fasterxml.jackson.databind.ObjectMapper;
import com.teknolojikpanda.bitbucket.aireviewer.util.HttpClientUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;
import java.io.IOException;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * Periodically probes the configured Ollama models so we can mark them degraded before user traffic fails.
 */
@Named
@Singleton
public class ModelHealthProbeScheduler implements LifecycleAware, DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(ModelHealthProbeScheduler.class);
    private static final JobRunnerKey JOB_RUNNER_KEY =
            JobRunnerKey.of("com.teknolojikpanda.bitbucket.ai-code-reviewer:model-health-probe-runner");
    private static final JobId JOB_ID =
            JobId.of("com.teknolojikpanda.bitbucket.ai-code-reviewer:model-health-probe-job");
    private static final long PROBE_INTERVAL_MS = TimeUnit.MINUTES.toMillis(1);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final SchedulerService schedulerService;
    private final AIReviewerConfigService configService;
    private final HttpClientUtil httpClientUtil;
    private final ModelHealthService modelHealthService;
    private final JobRunner runner = new ProbeRunner();
    private volatile boolean lifecycleStarted;

    @Inject
    public ModelHealthProbeScheduler(@ComponentImport SchedulerService schedulerService,
                                     AIReviewerConfigService configService,
                                     HttpClientUtil httpClientUtil,
                                     ModelHealthService modelHealthService) {
        this.schedulerService = Objects.requireNonNull(schedulerService, "schedulerService");
        this.configService = Objects.requireNonNull(configService, "configService");
        this.httpClientUtil = Objects.requireNonNull(httpClientUtil, "httpClientUtil");
        this.modelHealthService = Objects.requireNonNull(modelHealthService, "modelHealthService");
    }

    @Override
    public void onStart() {
        schedulerService.unregisterJobRunner(JOB_RUNNER_KEY);
        schedulerService.registerJobRunner(JOB_RUNNER_KEY, runner);
        lifecycleStarted = true;
        scheduleProbe();
    }

    @Override
    public void onStop() {
        shutdown();
    }

    @Override
    public void destroy() {
        shutdown();
    }

    private void scheduleProbe() {
        if (!lifecycleStarted) {
            return;
        }
        schedulerService.unscheduleJob(JOB_ID);
        JobConfig jobConfig = JobConfig.forJobRunnerKey(JOB_RUNNER_KEY)
                .withRunMode(RunMode.RUN_LOCALLY)
                .withSchedule(Schedule.forInterval(
                        PROBE_INTERVAL_MS,
                        new Date(System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(10))));
        try {
            schedulerService.scheduleJob(JOB_ID, jobConfig);
            log.debug("Scheduled model health probes every {} seconds", PROBE_INTERVAL_MS / 1000);
        } catch (SchedulerServiceException ex) {
            log.warn("Failed to schedule model health probe job", ex);
        }
    }

    private void shutdown() {
        lifecycleStarted = false;
        schedulerService.unscheduleJob(JOB_ID);
        schedulerService.unregisterJobRunner(JOB_RUNNER_KEY);
    }

    private class ProbeRunner implements JobRunner {
        @Override
        public JobRunnerResponse runJob(JobRunnerRequest request) {
            try {
                Map<String, Object> config = configService.getConfigurationAsMap();
                String baseUrl = stringValue(config.get("ollamaUrl"), "http://0.0.0.0:11434");
                probeModel(baseUrl, stringValue(config.get("ollamaModel"), ""));
                probeModel(baseUrl, stringValue(config.get("fallbackModel"), ""));
                return JobRunnerResponse.success("Model health probes executed");
            } catch (Exception ex) {
                log.debug("Model health probe failed: {}", ex.getMessage(), ex);
                return JobRunnerResponse.failed(ex);
            }
        }
    }

    private void probeModel(String baseUrl, String model) {
        if (model.isEmpty()) {
            return;
        }
        String endpoint = normalizeEndpoint(baseUrl);
        String chatUrl = endpoint.endsWith("/")
                ? endpoint + "api/chat"
                : endpoint + "/api/chat";

        String payload = buildProbePayload(model);
        if (payload == null) {
            return;
        }
        long started = System.currentTimeMillis();
        try {
            httpClientUtil.postJson(chatUrl, payload, 0);
            long duration = System.currentTimeMillis() - started;
            modelHealthService.recordSuccess(endpoint, model, duration);
        } catch (IOException ex) {
            modelHealthService.recordFailure(endpoint, model, ex.getMessage());
            log.warn("Model health probe failed for {} @ {}: {}", model, endpoint, ex.getMessage());
        }
    }

    private String buildProbePayload(String model) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("model", model);
        request.put("stream", Boolean.FALSE);
        request.put("temperature", 0);

        List<Map<String, Object>> messages = List.of(
                message("system", "You are a health probe for Bitbucket Guardrails. Respond concisely."),
                message("user", "Reply with the word PONG.")
        );
        request.put("messages", messages);
        try {
            return OBJECT_MAPPER.writeValueAsString(request);
        } catch (Exception ex) {
            log.warn("Failed to build model health probe payload: {}", ex.getMessage());
            return null;
        }
    }

    private Map<String, Object> message(String role, String content) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("role", role);
        payload.put("content", content);
        return payload;
    }

    private String stringValue(Object value, String defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        String str = value.toString().trim();
        return str.isEmpty() ? defaultValue : str;
    }

    private String normalizeEndpoint(String value) {
        if (value == null || value.trim().isEmpty()) {
            return "http://0.0.0.0:11434";
        }
        return value.trim().replaceAll("/+$", "");
    }
}
