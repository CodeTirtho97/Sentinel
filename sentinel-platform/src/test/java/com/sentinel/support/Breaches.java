package com.sentinel.support;

import com.sentinel.events.SloBreachEvent;
import com.sentinel.slo.domain.Severity;
import com.sentinel.slo.domain.SloType;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/** Builders for breach events, so the tests read as scenarios rather than as constructor calls. */
public final class Breaches {

    public static final Duration INTERVAL = Duration.ofSeconds(15);

    private Breaches() {}

    /**
     * A breach with a derived id, exactly as the evaluator would produce it.
     *
     * <p>Timestamps run backwards from the clock's "now" because correlation reads a window ending
     * at now — a breach stamped in the future is outside its own window and silently correlates to
     * nothing.
     */
    public static SloBreachEvent breach(String serviceName, Severity severity, Instant detectedAt) {
        return SloBreachEvent.of(
                sloIdFor(serviceName), serviceName, SloType.AVAILABILITY, severity, 22.1, 18.4, detectedAt, INTERVAL);
    }

    public static SloBreachEvent critical(String serviceName, Instant detectedAt) {
        return breach(serviceName, Severity.CRITICAL, detectedAt);
    }

    /** Stable per service, so two breaches for one service share an SLO the way production does. */
    public static UUID sloIdFor(String serviceName) {
        return UUID.nameUUIDFromBytes(("slo:" + serviceName).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}
