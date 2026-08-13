package com.sentinel.events;

import com.sentinel.slo.domain.Severity;
import com.sentinel.slo.domain.SloType;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * One SLO breach detected by one evaluation cycle.
 *
 * <p>{@code eventId} is derived, never random. A redelivered or re-evaluated breach produces the
 * same id, which is what makes consumer dedupe possible at all.
 */
public record SloBreachEvent(
        UUID eventId,
        UUID sloId,
        String serviceName,
        SloType sloType,
        Severity severity,
        double longBurnRate,
        double shortBurnRate,
        Instant detectedAt) {

    public SloBreachEvent {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(sloId, "sloId");
        Objects.requireNonNull(serviceName, "serviceName");
        Objects.requireNonNull(sloType, "sloType");
        Objects.requireNonNull(severity, "severity");
        Objects.requireNonNull(detectedAt, "detectedAt");
    }

    /**
     * Builds an event whose id is a deterministic function of (sloId, severity, evaluation bucket).
     *
     * <p>The bucket is {@code detectedAt} floored to the evaluation interval, so two cycles that see
     * the same ongoing breach within one interval collapse to one id. Consecutive intervals stay
     * distinct, so a breach that persists still produces a fresh timeline entry each cycle.
     */
    public static SloBreachEvent of(
            UUID sloId,
            String serviceName,
            SloType sloType,
            Severity severity,
            double longBurnRate,
            double shortBurnRate,
            Instant detectedAt,
            Duration evaluationInterval) {
        return new SloBreachEvent(
                deterministicId(sloId, severity, detectedAt, evaluationInterval),
                sloId,
                serviceName,
                sloType,
                severity,
                longBurnRate,
                shortBurnRate,
                detectedAt);
    }

    /**
     * Name-based UUID over the identity of the breach.
     *
     * <p>{@link UUID#nameUUIDFromBytes} is MD5-based, so this is a <b>v3</b> UUID — the JDK has no
     * v5 factory. Determinism is the property being bought here, and v3 has it; the version nibble
     * is checkable, so this is called v3 rather than v5 everywhere it is described.
     */
    public static UUID deterministicId(UUID sloId, Severity severity, Instant detectedAt, Duration interval) {
        long intervalMillis = interval.toMillis();
        if (intervalMillis <= 0) {
            throw new IllegalArgumentException("evaluation interval must be positive: " + interval);
        }
        long bucket = Math.floorDiv(detectedAt.toEpochMilli(), intervalMillis);
        String seed = sloId + "|" + severity.name() + "|" + bucket;
        return UUID.nameUUIDFromBytes(seed.getBytes(StandardCharsets.UTF_8));
    }
}
