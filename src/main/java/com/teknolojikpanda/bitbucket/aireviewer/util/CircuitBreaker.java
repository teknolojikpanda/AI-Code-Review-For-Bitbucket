package com.teknolojikpanda.bitbucket.aireviewer.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Circuit breaker implementation to protect against cascading failures.
 *
 * The circuit breaker operates in three states:
 * - CLOSED: Normal operation, requests are allowed through
 * - OPEN: Failure threshold exceeded, requests are blocked
 * - HALF_OPEN: Testing if service has recovered
 *
 * Based on the pattern described in "Release It!" by Michael Nygard.
 */
public class CircuitBreaker {

    private static final Logger log = LoggerFactory.getLogger(CircuitBreaker.class);

    private enum State {
        CLOSED,
        OPEN,
        HALF_OPEN
    }

    private final String name;
    private final int failureThreshold;
    private final Duration timeout;
    private final AtomicInteger failureCount;
    private final AtomicReference<State> state;
    private final AtomicReference<Instant> lastFailureTime;

    /**
     * Creates a new circuit breaker.
     *
     * @param name unique name for this circuit breaker (for logging)
     * @param failureThreshold number of failures before opening the circuit
     * @param timeout duration to wait before attempting to close the circuit
     */
    public CircuitBreaker(@Nonnull String name, int failureThreshold, @Nonnull Duration timeout) {
        this.name = name;
        this.failureThreshold = failureThreshold;
        this.timeout = timeout;
        this.failureCount = new AtomicInteger(0);
        this.state = new AtomicReference<>(State.CLOSED);
        this.lastFailureTime = new AtomicReference<>(null);
    }

    /**
     * Checks if the circuit breaker is open (blocking requests).
     *
     * @return true if the circuit is open, false otherwise
     */
    public boolean isOpen() {
        State currentState = state.get();

        if (currentState == State.OPEN) {
            // Check if timeout has elapsed
            Instant lastFailure = lastFailureTime.get();
            if (lastFailure != null && Duration.between(lastFailure, Instant.now()).compareTo(timeout) > 0) {
                log.info("Circuit breaker [{}] transitioning from OPEN to HALF_OPEN", name);
                state.compareAndSet(State.OPEN, State.HALF_OPEN);
                return false;
            }
            return true;
        }

        return false;
    }

    /**
     * Executes the given operation with circuit breaker protection.
     *
     * @param operation the operation to execute
     * @param <T> the return type
     * @return the result of the operation
     * @throws CircuitBreakerOpenException if the circuit is open
     * @throws Exception if the operation fails
     */
    public <T> T execute(@Nonnull Operation<T> operation) throws Exception {
        if (isOpen()) {
            throw new CircuitBreakerOpenException("Circuit breaker [" + name + "] is OPEN");
        }

        try {
            T result = operation.execute();
            onSuccess();
            return result;
        } catch (Exception e) {
            recordFailure();
            throw e;
        }
    }

    /**
     * Records a failure and potentially opens the circuit.
     */
    public void recordFailure() {
        int failures = failureCount.incrementAndGet();
        lastFailureTime.set(Instant.now());

        State currentState = state.get();

        if (currentState == State.HALF_OPEN) {
            // Any failure in half-open state immediately opens the circuit
            log.warn("Circuit breaker [{}] transitioning from HALF_OPEN to OPEN (failure during recovery test)", name);
            state.set(State.OPEN);
            failureCount.set(failureThreshold); // Ensure we stay open
        } else if (currentState == State.CLOSED && failures >= failureThreshold) {
            log.warn("Circuit breaker [{}] transitioning from CLOSED to OPEN (failures: {})", name, failures);
            state.set(State.OPEN);
        } else {
            log.debug("Circuit breaker [{}] recorded failure {}/{}", name, failures, failureThreshold);
        }
    }

    /**
     * Resets the circuit breaker to closed state.
     */
    public void reset() {
        log.info("Circuit breaker [{}] manually reset", name);
        failureCount.set(0);
        state.set(State.CLOSED);
        lastFailureTime.set(null);
    }

    /**
     * Called when an operation succeeds.
     */
    private void onSuccess() {
        State currentState = state.get();

        if (currentState == State.HALF_OPEN) {
            log.info("Circuit breaker [{}] transitioning from HALF_OPEN to CLOSED (recovery successful)", name);
            reset();
        } else if (currentState == State.CLOSED && failureCount.get() > 0) {
            // Gradually decrease failure count on success
            failureCount.decrementAndGet();
            log.debug("Circuit breaker [{}] decremented failure count to {}", name, failureCount.get());
        }
    }

    /**
     * Gets the current state of the circuit breaker.
     *
     * @return the current state
     */
    @Nonnull
    public String getState() {
        return state.get().name();
    }

    /**
     * Gets the current failure count.
     *
     * @return the number of recorded failures
     */
    public int getFailureCount() {
        return failureCount.get();
    }

    /**
     * Functional interface for operations protected by the circuit breaker.
     *
     * @param <T> the return type of the operation
     */
    @FunctionalInterface
    public interface Operation<T> {
        T execute() throws Exception;
    }

    /**
     * Exception thrown when attempting to execute an operation while the circuit is open.
     */
    public static class CircuitBreakerOpenException extends RuntimeException {
        public CircuitBreakerOpenException(String message) {
            super(message);
        }
    }
}
