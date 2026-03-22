package com.teknolojikpanda.bitbucket.aireviewer.service;

import com.atlassian.scheduler.JobRunner;
import com.atlassian.scheduler.JobRunnerResponse;
import com.atlassian.scheduler.SchedulerService;
import com.atlassian.scheduler.config.JobConfig;
import com.atlassian.scheduler.status.RunOutcome;
import com.teknolojikpanda.bitbucket.aireviewer.util.HttpClientUtil;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.mock;

public class ModelHealthProbeSchedulerTest {

    private SchedulerService schedulerService;
    private AIReviewerConfigService configService;
    private HttpClientUtil httpClientUtil;
    private ModelHealthService modelHealthService;

    @Before
    public void setUp() {
        schedulerService = mock(SchedulerService.class);
        configService = mock(AIReviewerConfigService.class);
        httpClientUtil = mock(HttpClientUtil.class);
        modelHealthService = mock(ModelHealthService.class);
    }

    @Test
    public void runJobRejectsLocalhostByPolicyWithoutOutboundCall() throws Exception {
        Map<String, Object> config = new HashMap<>();
        config.put("ollamaUrl", "http://localhost:11434");
        config.put("ollamaModel", "primary-model");
        config.put("fallbackModel", "fallback-model");
        when(configService.getConfigurationAsMap()).thenReturn(config);

        ModelHealthProbeScheduler scheduler = new ModelHealthProbeScheduler(
                schedulerService,
                configService,
                httpClientUtil,
                modelHealthService);
        scheduler.onStart();
        JobRunner runner = captureRunner();

        JobRunnerResponse response = runner.runJob(null);

        verify(httpClientUtil, never()).postJson(anyString(), anyString(), anyInt());
        verify(modelHealthService).recordFailure(eq("http://localhost:11434"), eq("primary-model"), contains("outbound URL policy"));
        verify(modelHealthService).recordFailure(eq("http://localhost:11434"), eq("fallback-model"), contains("outbound URL policy"));
        assertEquals(RunOutcome.SUCCESS, response.getRunOutcome());
    }

    @Test
    public void runJobSkipsWhenOllamaUrlMissing() throws Exception {
        Map<String, Object> config = new HashMap<>();
        config.put("ollamaModel", "primary-model");
        config.put("fallbackModel", "fallback-model");
        when(configService.getConfigurationAsMap()).thenReturn(config);

        ModelHealthProbeScheduler scheduler = new ModelHealthProbeScheduler(
                schedulerService,
                configService,
                httpClientUtil,
                modelHealthService);
        scheduler.onStart();
        JobRunner runner = captureRunner();

        JobRunnerResponse response = runner.runJob(null);

        verify(httpClientUtil, never()).postJson(anyString(), anyString(), anyInt());
        verify(modelHealthService, never()).recordFailure(anyString(), anyString(), anyString());
        verify(modelHealthService, never()).recordSuccess(anyString(), anyString(), anyLong());
        assertEquals(RunOutcome.SUCCESS, response.getRunOutcome());
    }

    @Test
    public void runJobProbesAllowedPublicEndpoint() throws Exception {
        Map<String, Object> config = new HashMap<>();
        config.put("ollamaUrl", "https://8.8.8.8:443");
        config.put("ollamaModel", "primary-model");
        config.put("fallbackModel", "fallback-model");
        when(configService.getConfigurationAsMap()).thenReturn(config);
        when(httpClientUtil.postJson(anyString(), anyString(), anyInt())).thenReturn("{\"done\":true}");

        ModelHealthProbeScheduler scheduler = new ModelHealthProbeScheduler(
                schedulerService,
                configService,
                httpClientUtil,
                modelHealthService);
        scheduler.onStart();
        JobRunner runner = captureRunner();

        JobRunnerResponse response = runner.runJob(null);

        verify(httpClientUtil, times(2)).postJson(anyString(), anyString(), eq(0));
        verify(modelHealthService).recordSuccess(eq("https://8.8.8.8:443"), eq("primary-model"), anyLong());
        verify(modelHealthService).recordSuccess(eq("https://8.8.8.8:443"), eq("fallback-model"), anyLong());
        assertEquals(RunOutcome.SUCCESS, response.getRunOutcome());
    }

    private JobRunner captureRunner() throws Exception {
        ArgumentCaptor<JobRunner> captor = ArgumentCaptor.forClass(JobRunner.class);
        verify(schedulerService).registerJobRunner(eq(
                com.atlassian.scheduler.config.JobRunnerKey.of(
                        "com.teknolojikpanda.bitbucket.ai-code-reviewer:model-health-probe-runner")), captor.capture());
        verify(schedulerService).scheduleJob(eq(
                com.atlassian.scheduler.config.JobId.of(
                        "com.teknolojikpanda.bitbucket.ai-code-reviewer:model-health-probe-job")), any(JobConfig.class));
        return captor.getValue();
    }
}
