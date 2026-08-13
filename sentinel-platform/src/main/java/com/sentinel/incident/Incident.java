package com.sentinel.incident;

import com.sentinel.slo.domain.Severity;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

@Entity
@Table(name = "incident")
public class Incident {

    @Id
    private UUID id;

    /**
     * The origin service, frozen at creation.
     *
     * <p>Never re-keyed. A member set hash would change on every new breach as a cascade spreads,
     * producing one incident per breach — exactly the alert storm this system exists to collapse.
     */
    @Column(name = "correlation_key", nullable = false)
    private String correlationKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false, length = 32)
    private IncidentState state;

    @Enumerated(EnumType.STRING)
    @Column(name = "severity", nullable = false, length = 32)
    private Severity severity;

    /** Recomputed as the timeline grows. Display only — the correlation key does not follow it. */
    @Column(name = "origin_service")
    private String originService;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "incident_affected_service", joinColumns = @JoinColumn(name = "incident_id"))
    @Column(name = "service_name", nullable = false)
    private Set<String> affectedServices = new LinkedHashSet<>();

    @Column(name = "opened_at", nullable = false)
    private Instant openedAt;

    @Column(name = "acknowledged_at")
    private Instant acknowledgedAt;

    @Column(name = "mitigated_at")
    private Instant mitigatedAt;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @Column(name = "last_breach_at", nullable = false)
    private Instant lastBreachAt;

    @Column(name = "breach_count", nullable = false)
    private int breachCount;

    /** Null until the RCA consumer has drafted one. An incident is useful before this exists. */
    @Column(name = "rca_draft", columnDefinition = "text")
    private String rcaDraft;

    /** The model that wrote the draft, or {@code template} for the deterministic fallback. */
    @Column(name = "rca_model", length = 128)
    private String rcaModel;

    @Column(name = "rca_generated_at")
    private Instant rcaGeneratedAt;

    /** True when the model was unavailable and the timeline summary stood in for it. */
    @Column(name = "rca_fallback")
    private Boolean rcaFallback;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    protected Incident() {}

    /**
     * Applies a transition or refuses it.
     *
     * <p>The guard lives on the entity rather than in the service so no code path can move an
     * incident sideways by setting the field directly.
     */
    public void transitionTo(IncidentState target, Instant now) {
        if (!state.canTransitionTo(target)) {
            throw new IllegalStateTransitionException(id, state, target);
        }
        this.state = target;
        switch (target) {
            case ACKNOWLEDGED -> this.acknowledgedAt = now;
            case MITIGATED -> this.mitigatedAt = now;
            case RESOLVED -> this.resolvedAt = now;
            case OPEN -> {
                /* unreachable: nothing transitions back to OPEN */
            }
        }
    }

    /** Widens the incident with one more member breach. Union, never replace. */
    public void recordBreach(Severity breachSeverity, Set<String> component, Instant detectedAt) {
        this.severity = Severity.max(this.severity, breachSeverity);
        this.affectedServices.addAll(component);
        if (detectedAt.isAfter(this.lastBreachAt)) {
            this.lastBreachAt = detectedAt;
        }
        this.breachCount++;
    }

    public UUID getId() {
        return id;
    }

    public String getCorrelationKey() {
        return correlationKey;
    }

    public IncidentState getState() {
        return state;
    }

    public Severity getSeverity() {
        return severity;
    }

    public String getOriginService() {
        return originService;
    }

    public void setOriginService(String originService) {
        this.originService = originService;
    }

    public Set<String> getAffectedServices() {
        return affectedServices;
    }

    public Instant getOpenedAt() {
        return openedAt;
    }

    public Instant getAcknowledgedAt() {
        return acknowledgedAt;
    }

    public Instant getMitigatedAt() {
        return mitigatedAt;
    }

    public Instant getResolvedAt() {
        return resolvedAt;
    }

    public Instant getLastBreachAt() {
        return lastBreachAt;
    }

    public int getBreachCount() {
        return breachCount;
    }

    /*
     * There is deliberately no setter for the RCA fields.
     *
     * <p>Writing them through the entity means rewriting the whole row, which drags the draft into
     * the @Version contention this entity uses to protect its affected-services set — an operator
     * acknowledging an incident mid-draft would collide with it and get a 500, over columns that
     * cannot meaningfully conflict. IncidentRepository.updateRca is the only write path, and
     * keeping a second one here is how that lesson would quietly get relearned.
     */

    public boolean hasRca() {
        return rcaDraft != null;
    }

    public String getRcaDraft() {
        return rcaDraft;
    }

    public String getRcaModel() {
        return rcaModel;
    }

    public Instant getRcaGeneratedAt() {
        return rcaGeneratedAt;
    }

    public Boolean getRcaFallback() {
        return rcaFallback;
    }
}
