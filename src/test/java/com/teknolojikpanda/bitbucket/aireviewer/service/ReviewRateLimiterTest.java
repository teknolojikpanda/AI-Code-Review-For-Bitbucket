package com.teknolojikpanda.bitbucket.aireviewer.service;

import org.junit.Before;
import org.junit.Test;

import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class ReviewRateLimiterTest {

    private AIReviewerConfigService configService;

    @Before
    public void setUp() {
        configService = mock(AIReviewerConfigService.class);
        when(configService.getConfigurationAsMap()).thenReturn(
                Map.of("repoRateLimitPerHour", 1, "projectRateLimitPerHour", 0));
    }

    @Test
    public void autoSnoozeAllowsAdditionalRun() {
        ReviewRateLimiter limiter = new ReviewRateLimiter(configService);
        limiter.acquire("PRJ", "repo"); // consumes the only token
        assertThrows(RateLimitExceededException.class, () -> limiter.acquire("PRJ", "repo"));

        limiter.autoSnooze("PRJ", "repo", TimeUnit.MINUTES.toMillis(5), "test");
        limiter.acquire("PRJ", "repo"); // does not throw thanks to snooze
    }

    @Test
    public void snoozeExpiresAfterDuration() throws Exception {
        ReviewRateLimiter limiter = new ReviewRateLimiter(configService);
        limiter.acquire("PRJ", "repo");
        assertThrows(RateLimitExceededException.class, () -> limiter.acquire("PRJ", "repo"));

        limiter.autoSnooze("PRJ", "repo", 10, "short");
        java.lang.reflect.Field snoozesField =
                ReviewRateLimiter.class.getDeclaredField("repoSnoozes");
        snoozesField.setAccessible(true);
        @SuppressWarnings("unchecked")
        java.util.concurrent.ConcurrentHashMap<String, Long> snoozes =
                (java.util.concurrent.ConcurrentHashMap<String, Long>) snoozesField.get(limiter);
        snoozes.put("repo", System.currentTimeMillis() - 1);

        assertThrows(RateLimitExceededException.class, () -> limiter.acquire("PRJ", "repo"));
    }
}
