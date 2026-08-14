package com.sentinel.incident;

import static org.assertj.core.api.Assertions.assertThat;

import com.sentinel.correlation.BreachRef;
import com.sentinel.correlation.CorrelationResult;
import com.sentinel.events.SloBreachEvent;
import com.sentinel.incident.api.IncidentResponses;
import com.sentinel.slo.domain.Severity;
import com.sentinel.support.AbstractIntegrationTest;
import com.sentinel.support.Breaches;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;

class IncidentApiIT extends AbstractIntegrationTest {

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private IncidentService service;

    @Test
    @DisplayName("an incident is listed, fetched with its timeline, and filterable")
    void listAndFetch() {
        UUID id = openIncident("ledger-service");

        var list = rest.getForEntity("/api/v1/incidents", IncidentResponses.Summary[].class);
        assertThat(list.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(list.getBody()).hasSize(1);
        assertThat(list.getBody()[0].originService()).isEqualTo("ledger-service");

        var detail = rest.getForEntity("/api/v1/incidents/" + id, IncidentResponses.Detail.class);
        assertThat(detail.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(detail.getBody().incident().severity()).isEqualTo(Severity.CRITICAL);

        // Exactly one BREACH entry. Not "exactly one entry": the RCA consumer adds its own line
        // moments after the incident opens, so an exact total is a race with a background consumer
        // rather than an assertion about the API.
        assertThat(detail.getBody().timeline())
                .filteredOn(entry -> entry.kind() == IncidentEventLog.Kind.BREACH)
                .singleElement()
                .satisfies(entry -> {
                    assertThat(entry.serviceName()).isEqualTo("ledger-service");
                    assertThat(entry.severity()).isEqualTo(Severity.CRITICAL);
                    assertThat(entry.longBurnRate()).isNotNull();
                });

        assertThat(rest.getForEntity("/api/v1/incidents?state=OPEN", IncidentResponses.Summary[].class)
                        .getBody())
                .hasSize(1);
        assertThat(rest.getForEntity("/api/v1/incidents?state=RESOLVED", IncidentResponses.Summary[].class)
                        .getBody())
                .isEmpty();
    }

    @Test
    @DisplayName("an unknown incident is 404, not an empty 200")
    void unknownIncidentIsNotFound() {
        var response = rest.getForEntity("/api/v1/incidents/" + UUID.randomUUID(), ProblemDetail.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("a legal transition returns 200 and the new state")
    void legalTransition() {
        UUID id = openIncident("ledger-service");

        var response = rest.postForEntity(
                "/api/v1/incidents/" + id + "/transition",
                Map.of("to", "ACKNOWLEDGED"),
                IncidentResponses.Summary.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().state()).isEqualTo(IncidentState.ACKNOWLEDGED);
    }

    @Test
    @DisplayName("an illegal transition is 409 with an RFC 7807 body naming the allowed targets")
    void illegalTransitionIsConflict() {
        UUID id = openIncident("ledger-service");

        ResponseEntity<ProblemDetail> response = rest.postForEntity(
                "/api/v1/incidents/" + id + "/transition", Map.of("to", "MITIGATED"), ProblemDetail.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().getTitle()).isEqualTo("Illegal state transition");
        assertThat(response.getBody().getProperties()).containsKeys("from", "to", "allowed");

        assertThat(service.get(id).getState()).isEqualTo(IncidentState.OPEN);
    }

    @Test
    @DisplayName("the services endpoint exposes the topology correlation reasons over")
    void serviceGraphIsExposed() {
        var response = rest.getForEntity("/api/v1/services", IncidentResponses.ServiceGraph.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().services())
                .extracting(IncidentResponses.ServiceNode::name)
                .contains("checkout-service", "cart-service", "payment-service", "ledger-service");
        assertThat(response.getBody().edges())
                .contains(new IncidentResponses.Edge("payment-service", "ledger-service"));
    }

    private UUID openIncident(String serviceName) {
        SloBreachEvent event = Breaches.critical(serviceName, clock.instant());
        return service.openOrAttach(new CorrelationResult(Set.of(serviceName), serviceName, List.of(BreachRef.of(event))), event)
                .incidentId();
    }
}
