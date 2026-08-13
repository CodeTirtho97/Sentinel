package com.sentinel.rca;

import com.sentinel.slo.domain.Severity;
import com.sentinel.slo.domain.SloType;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Everything the drafter is allowed to reason from.
 *
 * <p>Deliberately a compact structured summary rather than a raw dump. Two reasons: a prompt that
 * grows with incident size eventually stops fitting, and a model given a wall of undifferentiated
 * log lines produces a wall of undifferentiated prose. The timeline below is the entire evidence
 * base, which is what makes "use only the data provided" a checkable instruction rather than a
 * hopeful one.
 */
public record IncidentContext(
        UUID incidentId,
        Instant openedAt,
        Severity severity,
        String originService,
        Set<String> affectedServices,
        List<Edge> dependencyEdges,
        List<BreachEntry> timeline) {

    /** One configured dependency: {@code from} calls {@code to}. */
    public record Edge(String from, String to) {}

    /** One breach on the incident's timeline, oldest first. */
    public record BreachEntry(
            Instant detectedAt,
            String serviceName,
            SloType sloType,
            Severity severity,
            double longBurnRate,
            double shortBurnRate) {}
}
