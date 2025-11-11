package com.teknolojikpanda.bitbucket.aireviewer.service;

import org.junit.Before;
import org.junit.Test;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class GuardrailsAlertingServiceTest {

    private GuardrailsTelemetryService telemetryService;
    private GuardrailsAlertChannelService channelService;
    private AIReviewerConfigService configService;
    private GuardrailsAlertingService alertingService;

    @Before
    public void setUp() {
        telemetryService = mock(GuardrailsTelemetryService.class);
        channelService = mock(GuardrailsAlertChannelService.class);
        configService = mock(AIReviewerConfigService.class);
        alertingService = new GuardrailsAlertingService(telemetryService, channelService, configService);
    }

    @Test
    public void queueSaturationProducesCriticalAlert() {
        Map<String, Object> runtime = Map.of(
                "queue", Map.of(
                        "maxConcurrent", 2,
                        "active", 2,
                        "waiting", 3
                ),
                "retention", Map.of(
                        "schedule", Map.of(
                                "enabled", true,
                                "lastRun", System.currentTimeMillis()
                        ),
                        "recentRuns", Collections.emptyList()
                ),
                "rateLimiter", Collections.emptyMap()
        );
        when(telemetryService.collectRuntimeSnapshot()).thenReturn(runtime);

        GuardrailsAlertingService.AlertSnapshot snapshot = alertingService.evaluateAlerts();

        assertTrue(snapshot.getAlerts().stream()
                .anyMatch(alert -> "critical".equals(alert.get("severity"))
                        && alert.get("summary").toString().contains("queue saturated")));
    }

    @Test
    public void staleCleanupProducesWarning() {
        Map<String, Object> runtime = Map.of(
                "queue", Collections.emptyMap(),
                "retention", Map.of(
                        "schedule", Map.of(
                                "enabled", true,
                                "lastRun", System.currentTimeMillis() - (48 * 60 * 60 * 1000L)
                        ),
                        "recentRuns", Collections.emptyList()
                ),
                "rateLimiter", Collections.emptyMap()
        );
        when(telemetryService.collectRuntimeSnapshot()).thenReturn(runtime);

        GuardrailsAlertingService.AlertSnapshot snapshot = alertingService.evaluateAlerts();

        assertTrue(snapshot.getAlerts().stream()
                .anyMatch(alert -> "Cleanup stale".equals(alert.get("summary"))));
    }

    @Test
    public void noAlertsWhenHealthy() {
        Map<String, Object> schedule = new java.util.LinkedHashMap<>();
        schedule.put("enabled", true);
        schedule.put("lastRun", System.currentTimeMillis());
        schedule.put("lastError", null);
        Map<String, Object> retention = new java.util.LinkedHashMap<>();
        retention.put("schedule", schedule);
        retention.put("recentRuns", List.of(Map.of("success", true)));
        Map<String, Object> runtime = new java.util.LinkedHashMap<>();
        runtime.put("queue", Map.of(
                "maxConcurrent", 4,
                "active", 2,
                "waiting", 0
        ));
        runtime.put("retention", retention);
        runtime.put("rateLimiter", Collections.emptyMap());
        when(telemetryService.collectRuntimeSnapshot()).thenReturn(runtime);

        GuardrailsAlertingService.AlertSnapshot snapshot = alertingService.evaluateAlerts();

        assertFalse(snapshot.getAlerts().stream().anyMatch(alert -> !"info".equals(alert.get("severity"))));
    }

    @Test
    public void evaluateAndNotifyHitsChannels() {
        Map<String, Object> runtime = Map.of(
                "queue", Map.of("maxConcurrent", 1, "active", 1, "waiting", 1),
                "retention", Map.of("schedule", Map.of("enabled", true, "lastRun", System.currentTimeMillis()),
                        "recentRuns", Collections.emptyList()),
                "rateLimiter", Collections.emptyMap()
        );
        when(telemetryService.collectRuntimeSnapshot()).thenReturn(runtime);

        GuardrailsAlertingService.AlertSnapshot snapshot = alertingService.evaluateAndNotify();

        assertFalse(snapshot.getAlerts().isEmpty());
        verify(channelService).notifyChannels(snapshot);
    }

    @Test
    public void repoRateLimiterAlertTriggeredWhenThresholdExceeded() {
        Map<String, Object> rateLimiter = Map.of(
                "topRepoBuckets", List.of(
                        Map.of(
                                "scope", "repo-one",
                                "consumed", 9,
                                "limit", 10,
                                "remaining", 1,
                                "resetInMs", 600000L
                        )
                ),
                "topProjectBuckets", Collections.emptyList()
        );
        when(configService.resolveRepoRateLimitAlertPercent(eq("repo-one"))).thenReturn(80);

        List<Map<String, Object>> alerts = new java.util.ArrayList<>();
        alertingService.evaluateRateLimiterAlerts(rateLimiter, alerts);

        org.mockito.Mockito.verify(configService).resolveRepoRateLimitAlertPercent("repo-one");

        assertTrue(alerts.stream()
                .anyMatch(alert -> alert.get("summary").toString().contains("Repository rate limit")));
    }
}
