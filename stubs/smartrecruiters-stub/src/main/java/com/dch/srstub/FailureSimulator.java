package com.dch.srstub;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;

/**
 * Deterministic failure injection: the next {@code count} create requests fail with the given mode.
 */
@Component
public class FailureSimulator {

    private static final Logger log = LoggerFactory.getLogger(FailureSimulator.class);

    private FailurePlan plan;

    public synchronized void schedule(FailureMode mode, int count, Duration delay) {
        plan = new FailurePlan(mode, count, delay);
        log.info("Scheduled failure {}", plan);
    }

    public synchronized void reset() {
        plan = null;
    }

    public synchronized Optional<FailurePlan> current() {
        return Optional.ofNullable(plan);
    }

    synchronized Optional<FailurePlan> next() {
        if (plan == null || plan.remaining() <= 0) {
            return Optional.empty();
        }

        FailurePlan injected = plan;
        plan = plan.remaining() == 1 ? null : plan.withRemaining(plan.remaining() - 1);
        log.info("Injecting failure {} ({} left)", injected.mode(), injected.remaining() - 1);
        return Optional.of(injected);
    }

    public record FailurePlan(FailureMode mode, int remaining, Duration delay) {

        FailurePlan withRemaining(int remaining) {
            return new FailurePlan(mode, remaining, delay);
        }
    }
}
