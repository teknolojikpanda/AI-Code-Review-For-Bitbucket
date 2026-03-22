package com.teknolojikpanda.bitbucket.aireviewer.util;

import org.junit.Test;

import java.time.Duration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class HttpClientUtilTest {

    @Test
    public void constructorAppliesApiDelayToEffectivePacing() throws Exception {
        HttpClientUtil client = new HttpClientUtil(1000, 1000, 0, 10, 500);
        RateLimiter limiter = client.getRateLimiter();
        limiter.reset();

        long startNs = System.nanoTime();
        limiter.acquire();
        limiter.acquire();
        limiter.acquire();
        long elapsedMs = (System.nanoTime() - startNs) / 1_000_000L;

        assertTrue("Expected at most ~2 req/sec pacing for 500ms delay, elapsedMs=" + elapsedMs,
                elapsedMs >= 900);
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
