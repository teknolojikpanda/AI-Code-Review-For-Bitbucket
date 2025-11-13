package com.teknolojikpanda.bitbucket.aireviewer.util;

import org.junit.Test;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

public class CircuitBreakerTest {

    @Test
    public void breakerOpensAfterFailuresAndRecovers() throws Exception {
        CircuitBreaker breaker = new CircuitBreaker("ai-primary", 2, Duration.ofMillis(50));

        for (int i = 0; i < 2; i++) {
            int attempt = i;
            assertThrows(RuntimeException.class, () -> breaker.execute(() -> {
                throw new RuntimeException("boom-" + attempt);
            }));
        }

        assertEquals("OPEN", breaker.getState());
        assertThrows(CircuitBreaker.CircuitBreakerOpenException.class, () -> breaker.execute(() -> "should fail"));
        assertEquals(1, breaker.getBlockedCalls());

        TimeUnit.MILLISECONDS.sleep(60);

        String result = breaker.execute(() -> "recovered");
        assertEquals("recovered", result);
        assertEquals("CLOSED", breaker.getState());
    }

    @Test
    public void halfOpenFailureReopensImmediately() throws Exception {
        CircuitBreaker breaker = new CircuitBreaker("ai-secondary", 1, Duration.ofMillis(30));
        assertThrows(RuntimeException.class, () -> breaker.execute(() -> {
            throw new RuntimeException("fail");
        }));
        assertEquals("OPEN", breaker.getState());

        TimeUnit.MILLISECONDS.sleep(35);

        assertThrows(RuntimeException.class, () -> breaker.execute(() -> {
            throw new RuntimeException("still failing");
        }));
        assertEquals("OPEN", breaker.getState());
        assertEquals(2, breaker.getOpenEvents());
    }
}
