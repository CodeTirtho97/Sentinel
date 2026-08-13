package com.sentinel.incident;

import com.sentinel.slo.domain.Severity;
import com.sentinel.slo.domain.SloType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** One line of an incident's timeline. Breach entries carry the burn rates; lifecycle entries do not. */
@Entity
@Table(name = "incident_event_log")
public class IncidentEventLog {

    public enum Kind {
        BREACH,
        STATE_CHANGE,
        RCA
    }

    @Id
    private UUID id;

    @Column(name = "incident_id", nullable = false)
    private UUID incidentId;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 32)
    private Kind kind;

    /** The deterministic breach event id, or null for entries with no source event. */
    @Column(name = "event_id")
    private UUID eventId;

    @Column(name = "service_name")
    private String serviceName;

    @Enumerated(EnumType.STRING)
    @Column(name = "slo_type", length = 32)
    private SloType sloType;

    @Enumerated(EnumType.STRING)
    @Column(name = "severity", length = 32)
    private Severity severity;

    @Column(name = "long_burn_rate")
    private Double longBurnRate;

    @Column(name = "short_burn_rate")
    private Double shortBurnRate;

    @Column(name = "message")
    private String message;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    protected IncidentEventLog() {}

    public static IncidentEventLog breach(
            UUID incidentId,
            UUID eventId,
            String serviceName,
            SloType sloType,
            Severity severity,
            double longBurnRate,
            double shortBurnRate,
            Instant occurredAt) {
        var entry = new IncidentEventLog();
        entry.id = UUID.randomUUID();
        entry.incidentId = incidentId;
        entry.kind = Kind.BREACH;
        entry.eventId = eventId;
        entry.serviceName = serviceName;
        entry.sloType = sloType;
        entry.severity = severity;
        entry.longBurnRate = longBurnRate;
        entry.shortBurnRate = shortBurnRate;
        entry.occurredAt = occurredAt;
        return entry;
    }

    /** Marks the point on the timeline where a hypothesis was drafted, and by what. */
    public static IncidentEventLog rca(UUID incidentId, String message, Instant occurredAt) {
        var entry = new IncidentEventLog();
        entry.id = UUID.randomUUID();
        entry.incidentId = incidentId;
        entry.kind = Kind.RCA;
        entry.message = message;
        entry.occurredAt = occurredAt;
        return entry;
    }

    public static IncidentEventLog stateChange(UUID incidentId, String message, Instant occurredAt) {
        var entry = new IncidentEventLog();
        entry.id = UUID.randomUUID();
        entry.incidentId = incidentId;
        entry.kind = Kind.STATE_CHANGE;
        entry.message = message;
        entry.occurredAt = occurredAt;
        return entry;
    }

    public UUID getId() {
        return id;
    }

    public Kind getKind() {
        return kind;
    }

    public UUID getEventId() {
        return eventId;
    }

    public String getServiceName() {
        return serviceName;
    }

    public SloType getSloType() {
        return sloType;
    }

    public Severity getSeverity() {
        return severity;
    }

    public Double getLongBurnRate() {
        return longBurnRate;
    }

    public Double getShortBurnRate() {
        return shortBurnRate;
    }

    public String getMessage() {
        return message;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }
}
