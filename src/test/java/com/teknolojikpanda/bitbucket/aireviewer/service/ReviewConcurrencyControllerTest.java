package com.teknolojikpanda.bitbucket.aireviewer.service;

import org.junit.Before;
import org.junit.Test;

import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class ReviewConcurrencyControllerTest {

    private AIReviewerConfigService configService;
    private ReviewSchedulerStateService schedulerStateService;
    private ReviewQueueAuditService queueAuditService;

    @Before
    public void setUp() {
        configService = mock(AIReviewerConfigService.class);
        schedulerStateService = mock(ReviewSchedulerStateService.class);
        queueAuditService = mock(ReviewQueueAuditService.class);
    }

    @Test
    public void pausedSchedulerRejectsNewRuns() {
        ReviewConcurrencyController controller = new ReviewConcurrencyController(
                configService,
                schedulerStateService,
                queueAuditService);
        when(configService.getConfigurationAsMap()).thenReturn(Map.of());
        when(schedulerStateService.getState()).thenReturn(state(ReviewSchedulerStateService.SchedulerState.Mode.PAUSED));

        ReviewConcurrencyController.ReviewExecutionRequest request = buildRequest("run-paused");

        assertThrows(ReviewSchedulerPausedException.class, () -> controller.acquire(request));
        assertEquals(0, controller.getWaitingCount());
    }

    @Test
    public void queueLimitExceededRaisesQueueFull() throws Exception {
        ReviewConcurrencyController controller = new ReviewConcurrencyController(
                configService,
                schedulerStateService,
                queueAuditService);
        when(configService.getConfigurationAsMap()).thenReturn(Map.of(
                "maxConcurrentReviews", 1,
                "maxQueuedReviews", 0,
                "maxQueuedPerRepo", 0,
                "maxQueuedPerProject", 0));
        when(schedulerStateService.getState()).thenReturn(state(ReviewSchedulerStateService.SchedulerState.Mode.ACTIVE));

        ReviewConcurrencyController.ReviewExecutionRequest first = buildRequest("run-1");
        ReviewConcurrencyController.ReviewExecutionRequest second = buildRequest("run-2");

        try (ReviewConcurrencyController.Slot slot = controller.acquire(first)) {
            assertThrows(ReviewQueueFullException.class, () -> controller.acquire(second));
        }
    }

    private ReviewConcurrencyController.ReviewExecutionRequest buildRequest(String runId) {
        return new ReviewConcurrencyController.ReviewExecutionRequest(
                "PRJ",
                "repo",
                42L,
                false,
                false,
                false,
                runId,
                "alice",
                "pilot",
                GuardrailsRolloutService.RolloutMode.ENFORCED,
                true);
    }

    private ReviewSchedulerStateService.SchedulerState state(ReviewSchedulerStateService.SchedulerState.Mode mode) {
        return new ReviewSchedulerStateService.SchedulerState(
                mode,
                "admin",
                "Admin",
                "test",
                System.currentTimeMillis());
    }
}
