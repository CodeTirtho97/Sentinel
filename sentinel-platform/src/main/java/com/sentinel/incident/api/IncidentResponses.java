package com.sentinel.incident.api;

import com.sentinel.incident.Incident;
import com.sentinel.incident.IncidentEventLog;
import com.sentinel.incident.IncidentState;
import com.sentinel.slo.domain.Severity;
import com.sentinel.slo.domain.SloType;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

public final class IncidentResponses {

    private IncidentResponses() {}

    public record Summary(
            UUID id,
            String correlationKey,
            IncidentState state,
            Severity severity,
            String originService,
            Set<String> affectedServices,
            int breachCount,
            Instant openedAt,
            Instant lastBreachAt,
            Instant resolvedAt) {

        public static Summary from(Incident incident) {
            return new Summary(
                    incident.getId(),
                    incident.getCorrelationKey(),
                    incident.getState(),
                    incident.getSeverity(),
                    incident.getOriginService(),
                    new TreeSet<>(incident.getAffectedServices()),
                    incident.getBreachCount(),
                    incident.getOpenedAt(),
                    incident.getLastBreachAt(),
                    incident.getResolvedAt());
        }
    }

    public record Detail(Summary incident, List<TimelineEntry> timeline) {

        public static Detail from(Incident incident, List<IncidentEventLog> timeline) {
            return new Detail(
                    Summary.from(incident),
                    timeline.stream().map(TimelineEntry::from).toList());
        }
    }

    public record TimelineEntry(
            IncidentEventLog.Kind kind,
            UUID eventId,
            String serviceName,
            SloType sloType,
            Severity severity,
            Double longBurnRate,
            Double shortBurnRate,
            String message,
            Instant occurredAt) {

        public static TimelineEntry from(IncidentEventLog entry) {
            return new TimelineEntry(
                    entry.getKind(),
                    entry.getEventId(),
                    entry.getServiceName(),
                    entry.getSloType(),
                    entry.getSeverity(),
                    entry.getLongBurnRate(),
                    entry.getShortBurnRate(),
                    entry.getMessage(),
                    entry.getOccurredAt());
        }
    }

    public record TransitionRequest(@NotNull IncidentState to) {}

    /**
     * A drafted hypothesis, or a note that one is still being written.
     *
     * <p>{@code model} and {@code fallback} are part of the payload rather than internal bookkeeping:
     * a deterministic timeline summary and a model narrative warrant different amounts of trust, and
     * a reader who cannot tell which one they are looking at has no way to calibrate that.
     */
    public record Rca(
            UUID incidentId, String status, String draft, String model, Boolean fallback, Instant generatedAt) {

        public static Rca ready(Incident incident) {
            return new Rca(
                    incident.getId(),
                    "READY",
                    incident.getRcaDraft(),
                    incident.getRcaModel(),
                    incident.getRcaFallback(),
                    incident.getRcaGeneratedAt());
        }

        public static Rca pending(UUID incidentId) {
            return new Rca(incidentId, "PENDING", null, null, null, null);
        }
    }

    /** The fleet and its configured topology, for {@code GET /services}. */
    public record ServiceGraph(List<ServiceNode> services, List<Edge> edges) {}

    public record ServiceNode(String name, int depth) {}

    public record Edge(String from, String to) {}
}
