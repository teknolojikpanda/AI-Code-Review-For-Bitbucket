package com.teknolojikpanda.bitbucket.aireviewer.util;

import org.junit.Test;

import java.time.Duration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class HttpClientUtilTest {

    @Test
    public void constructorAppliesApiDelayToEffectivePacing() throws Exception {
        HttpClientUtil client = new HttpClientUtil(1000, 1000, 0, 10, 500);
        RateLimiter limiter = client.getRateLimiter();
        limiter.reset();

        // 1 request per 500ms window equals at most ~2 req/sec.
        assertEquals(1, limiter.getMaxRequests());
        assertEquals(Duration.ofMillis(500), limiter.getTimeWindow());

        assertTrue(limiter.tryAcquire());
        assertFalse("Second immediate acquire must be blocked by pacing window", limiter.tryAcquire());

        Thread.sleep(550);
        assertTrue("Acquire should succeed after pacing window elapses", limiter.tryAcquire());
    }

    @Test
    public void constructorUsesSafeDefaultWhenApiDelayNonPositive() {
        HttpClientUtil zeroDelay = new HttpClientUtil(1000, 1000, 0, 10, 0);
        assertEquals(100, zeroDelay.getEffectiveApiDelayMs());
        assertEquals(1, zeroDelay.getRateLimiter().getMaxRequests());
        assertEquals(Duration.ofMillis(100), zeroDelay.getRateLimiter().getTimeWindow());

        HttpClientUtil negativeDelay = new HttpClientUtil(1000, 1000, 0, 10, -250);
        assertEquals(100, negativeDelay.getEffectiveApiDelayMs());
        assertEquals(1, negativeDelay.getRateLimiter().getMaxRequests());
        assertEquals(Duration.ofMillis(100), negativeDelay.getRateLimiter().getTimeWindow());
    }
}
