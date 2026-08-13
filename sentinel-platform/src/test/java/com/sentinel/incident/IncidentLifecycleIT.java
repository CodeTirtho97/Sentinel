package com.sentinel.incident;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sentinel.correlation.CorrelationResult;
import com.sentinel.events.SloBreachEvent;
import com.sentinel.support.AbstractIntegrationTest;
import com.sentinel.support.Breaches;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Scenario 7 plus the lifecycle rules, driven by an injected clock rather than by waiting. */
class IncidentLifecycleIT extends AbstractIntegrationTest {

    @Autowired
    private IncidentService service;

    @Autowired
    private IncidentAutoResolver autoResolver;

    @Test
    @DisplayName("scenario 7: an incident with no recent breach auto-resolves when the clock advances")
    void quietIncidentAutoResolves() {
        UUID id = openIncident("ledger-service");

        // Not yet: still inside the ten-minute quiet period.
        clock.advance(Duration.ofMinutes(5));
        autoResolver.resolveQuietIncidents();
        assertThat(service.get(id).getState()).isEqualTo(IncidentState.OPEN);

        clock.advance(Duration.ofMinutes(6));
        autoResolver.resolveQuietIncidents();

        Incident resolved = service.get(id);
        assertThat(resolved.getState()).isEqualTo(IncidentState.RESOLVED);
        assertThat(resolved.getResolvedAt()).isNotNull();
    }

    @Test
    @DisplayName("an acknowledged incident still auto-resolves once the breaches stop")
    void acknowledgedIncidentAlsoAutoResolves() {
        UUID id = openIncident("ledger-service");
        service.transition(id, IncidentState.ACKNOWLEDGED, "test");

        clock.advance(Duration.ofMinutes(11));
        autoResolver.resolveQuietIncidents();

        assertThat(service.get(id).getState()).isEqualTo(IncidentState.RESOLVED);
    }

    @Test
    @DisplayName("a fresh breach keeps the incident alive")
    void recentBreachPreventsAutoResolution() {
        UUID id = openIncident("ledger-service");

        clock.advance(Duration.ofMinutes(9));
        attachBreach(id, "ledger-service", clock.instant());

        clock.advance(Duration.ofMinutes(5));
        autoResolver.resolveQuietIncidents();

        assertThat(service.get(id).getState()).isEqualTo(IncidentState.OPEN);
    }

    @Test
    @DisplayName("the legal path walks OPEN -> ACKNOWLEDGED -> MITIGATED -> RESOLVED with timestamps")
    void fullLifecycleStampsEveryStage() {
        UUID id = openIncident("ledger-service");

        clock.advance(Duration.ofMinutes(1));
        service.transition(id, IncidentState.ACKNOWLEDGED, "oncall");
        clock.advance(Duration.ofMinutes(1));
        service.transition(id, IncidentState.MITIGATED, "oncall");
        clock.advance(Duration.ofMinutes(1));
        Incident incident = service.transition(id, IncidentState.RESOLVED, "oncall");

        assertThat(incident.getState()).isEqualTo(IncidentState.RESOLVED);
        assertThat(incident.getAcknowledgedAt()).isNotNull();
        assertThat(incident.getMitigatedAt()).isNotNull();
        assertThat(incident.getResolvedAt()).isNotNull();
        assertThat(incident.getAcknowledgedAt()).isBefore(incident.getMitigatedAt());
        assertThat(incident.getMitigatedAt()).isBefore(incident.getResolvedAt());
    }

    @Test
    @DisplayName("skipping a stage is refused")
    void illegalTransitionThrows() {
        UUID id = openIncident("ledger-service");

        assertThatThrownBy(() -> service.transition(id, IncidentState.MITIGATED, "test"))
                .isInstanceOf(IllegalStateTransitionException.class);

        assertThat(service.get(id).getState()).isEqualTo(IncidentState.OPEN);
    }

    @Test
    @DisplayName("a resolved incident is terminal")
    void resolvedIsTerminal() {
        UUID id = openIncident("ledger-service");
        service.transition(id, IncidentState.RESOLVED, "test");

        assertThatThrownBy(() -> service.transition(id, IncidentState.ACKNOWLEDGED, "test"))
                .isInstanceOf(IllegalStateTransitionException.class);
    }

    @Test
    @DisplayName("every transition is appended to the timeline")
    void transitionsAreRecordedOnTheTimeline() {
        UUID id = openIncident("ledger-service");
        service.transition(id, IncidentState.ACKNOWLEDGED, "oncall");

        assertThat(service.timeline(id))
                .extracting(IncidentEventLog::getKind)
                .containsExactly(IncidentEventLog.Kind.BREACH, IncidentEventLog.Kind.STATE_CHANGE);
    }

    private UUID openIncident(String serviceName) {
        SloBreachEvent event = Breaches.critical(serviceName, clock.instant());
        return service.openOrAttach(new CorrelationResult(Set.of(serviceName), serviceName, List.of(event)), event)
                .incidentId();
    }

    private void attachBreach(UUID incidentId, String serviceName, java.time.Instant at) {
        SloBreachEvent event = Breaches.critical(serviceName, at);
        var outcome =
                service.openOrAttach(new CorrelationResult(Set.of(serviceName), serviceName, List.of(event)), event);
        assertThat(outcome.incidentId()).isEqualTo(incidentId);
    }
}
