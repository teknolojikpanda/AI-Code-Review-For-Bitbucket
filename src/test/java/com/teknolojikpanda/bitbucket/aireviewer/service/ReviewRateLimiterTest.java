package com.teknolojikpanda.bitbucket.aireviewer.service;

import org.junit.Before;
import org.junit.Test;

import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class ReviewRateLimiterTest {

    private AIReviewerConfigService configService;
    private GuardrailsRateLimitStore rateLimitStore;
    private GuardrailsRateLimitOverrideService overrideService;

    @Before
    public void setUp() {
        configService = mock(AIReviewerConfigService.class);
        when(configService.getConfigurationAsMap()).thenReturn(
                Map.of("repoRateLimitPerHour", 1, "projectRateLimitPerHour", 0));
        rateLimitStore = mock(GuardrailsRateLimitStore.class);
        overrideService = mock(GuardrailsRateLimitOverrideService.class);
        when(overrideService.resolveRepoLimit(any(), any(), anyInt())).thenAnswer(invocation -> invocation.getArgument(2));
        when(overrideService.resolveProjectLimit(any(), anyInt())).thenAnswer(invocation -> invocation.getArgument(1));
    }

    @Test
    public void autoSnoozeAllowsAdditionalRun() {
        AtomicInteger invocationCount = new AtomicInteger();
        doAnswer(invocation -> {
            if (invocationCount.incrementAndGet() > 1) {
                String identifier = invocation.getArgument(1);
                int limit = invocation.getArgument(2);
                throw RateLimitExceededException.repository(identifier, limit, 1_000L);
            }
            return null;
        }).when(rateLimitStore).acquireToken(eq(GuardrailsRateLimitScope.REPOSITORY),
                anyString(),
                anyInt(),
                anyLong(),
                anyLong(),
                anyLong());

        ReviewRateLimiter limiter = new ReviewRateLimiter(configService, rateLimitStore, overrideService);
        limiter.acquire("PRJ", "repo"); // consumes the only token
        assertThrows(RateLimitExceededException.class, () -> limiter.acquire("PRJ", "repo"));

        limiter.autoSnooze("PRJ", "repo", TimeUnit.MINUTES.toMillis(5), "test");
        limiter.acquire("PRJ", "repo"); // does not throw thanks to snooze
    }

    @Test
    public void snoozeExpiresAfterDuration() throws Exception {
        AtomicInteger invocationCount = new AtomicInteger();
        doAnswer(invocation -> {
            if (invocationCount.incrementAndGet() > 1) {
                String identifier = invocation.getArgument(1);
                int limit = invocation.getArgument(2);
                throw RateLimitExceededException.repository(identifier, limit, 500L);
            }
            return null;
        }).when(rateLimitStore).acquireToken(eq(GuardrailsRateLimitScope.REPOSITORY),
                anyString(),
                anyInt(),
                anyLong(),
                anyLong(),
                anyLong());

        ReviewRateLimiter limiter = new ReviewRateLimiter(configService, rateLimitStore, overrideService);
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
