package com.sentinel.correlation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.sentinel.events.SloBreachEvent;
import com.sentinel.events.Topics;
import com.sentinel.incident.Incident;
import com.sentinel.incident.IncidentState;
import com.sentinel.slo.domain.Severity;
import com.sentinel.support.AbstractIntegrationTest;
import com.sentinel.support.Breaches;
import com.sentinel.support.MutableClock;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;

/** Scenarios 1, 3 and 4: a breach becomes an incident, and only the right breaches join it. */
class BreachCorrelationIT extends AbstractIntegrationTest {

    @Autowired
    private KafkaTemplate<String, Object> kafka;

    @Test
    @DisplayName("scenario 1: one breach opens one incident with the right severity, origin and blast radius")
    void singleBreachOpensAnIncident() {
        SloBreachEvent breach = Breaches.critical("ledger-service", MutableClock.START);

        publish(breach);

        Incident incident = awaitSingleIncident();
        assertThat(incident.getState()).isEqualTo(IncidentState.OPEN);
        assertThat(incident.getSeverity()).isEqualTo(Severity.CRITICAL);
        assertThat(incident.getOriginService()).isEqualTo("ledger-service");
        assertThat(incident.getCorrelationKey()).isEqualTo("ledger-service");
        assertThat(incident.getAffectedServices()).containsExactly("ledger-service");
        assertThat(breachEntries(incident.getId())).hasSize(1);
    }

    /**
     * The headline behaviour: ledger breaks, the failure climbs the call chain, and what would be
     * four pages in a naive system is one incident naming ledger as the origin.
     *
     * <p>Each breach is awaited before the next is published, because that is how a cascade actually
     * reaches the consumer — one evaluation cycle detects ledger, a later cycle detects payment, and
     * so on. Firing all four at once instead would be testing something else: breaches are keyed by
     * service name and therefore spread across partitions, so a single burst has no defined
     * cross-service order, and a component is only correlated against the breaches recorded <i>so
     * far</i>. Processing checkout before ledger legitimately opens a checkout incident, and the
     * correlation key is frozen at creation, so the two never merge (§7). That ordering boundary is
     * covered explicitly by {@code ConsumerRestartIT}.
     */
    @Test
    @DisplayName("scenario 3: a four-service cascade collapses into ONE incident naming ledger as origin")
    void cascadeCollapsesToOneIncident() {
        publish(Breaches.critical("ledger-service", MutableClock.START.minusSeconds(45)));
        awaitAffectedCount(1);

        publish(Breaches.critical("payment-service", MutableClock.START.minusSeconds(30)));
        awaitAffectedCount(2);

        publish(Breaches.breach("cart-service", Severity.HIGH, MutableClock.START.minusSeconds(15)));
        awaitAffectedCount(3);

        publish(Breaches.critical("checkout-service", MutableClock.START));
        awaitAffectedCount(4);

        List<Incident> all = incidents.findAll();
        assertThat(all).hasSize(1);

        Incident incident = all.get(0);
        assertThat(incident.getAffectedServices())
                .containsExactlyInAnyOrder("ledger-service", "payment-service", "cart-service", "checkout-service");
        assertThat(incident.getOriginService()).isEqualTo("ledger-service");
        assertThat(incident.getCorrelationKey()).isEqualTo("ledger-service");
        // Severity is the maximum over members, so one HIGH member does not dilute it.
        assertThat(incident.getSeverity()).isEqualTo(Severity.CRITICAL);
        assertThat(incident.getBreachCount()).isEqualTo(4);
        assertThat(breachEntries(incident.getId())).hasSize(4);
    }

    private void awaitAffectedCount(int expected) {
        await().atMost(AWAIT_TIMEOUT).untilAsserted(() -> {
            List<Incident> all = incidents.findAll();
            assertThat(all).hasSize(1);
            assertThat(all.get(0).getAffectedServices()).hasSize(expected);
        });
    }

    @Test
    @DisplayName("scenario 4: breaches in unconnected components stay two incidents")
    void unconnectedBreachesDoNotCorrelate() {
        // checkout and ledger are joined in the full topology only through cart and payment, and
        // neither of those is breaching. Correlating over the full graph rather than the subgraph
        // induced by the breached set would wrongly merge these.
        publish(Breaches.critical("ledger-service", MutableClock.START.minusSeconds(30)));
        publish(Breaches.critical("checkout-service", MutableClock.START));

        await().atMost(AWAIT_TIMEOUT)
                .untilAsserted(() -> assertThat(incidents.findAll()).hasSize(2));

        assertThat(incidents.findAll())
                .extracting(Incident::getCorrelationKey)
                .containsExactlyInAnyOrder("ledger-service", "checkout-service");
        assertThat(incidents.findAll()).allSatisfy(incident -> assertThat(incident.getAffectedServices())
                .hasSize(1));
    }

    @Test
    @DisplayName("a later breach widens the existing incident instead of opening a second one")
    void secondBreachAttachesToTheSameIncident() {
        publish(Breaches.critical("ledger-service", MutableClock.START.minusSeconds(30)));
        Incident opened = awaitSingleIncident();

        publish(Breaches.critical("payment-service", MutableClock.START));

        await().atMost(AWAIT_TIMEOUT).untilAsserted(() -> {
            Incident reloaded = incidents.findById(opened.getId()).orElseThrow();
            assertThat(reloaded.getAffectedServices()).containsExactlyInAnyOrder("ledger-service", "payment-service");
        });
        assertThat(incidents.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("a breach outside the correlation window does not join the incident")
    void breachOutsideTheWindowIsNotCorrelated() {
        // Twenty minutes back, against a five-minute window: old enough that Redis has already
        // trimmed it, so it cannot pull payment-service into ledger-service's component.
        publish(Breaches.critical("ledger-service", MutableClock.START.minus(Duration.ofMinutes(20))));
        awaitSingleIncident();

        publish(Breaches.critical("payment-service", MutableClock.START));

        await().atMost(AWAIT_TIMEOUT)
                .untilAsserted(() -> assertThat(incidents.findAll()).hasSize(2));
        assertThat(incidents.findAll()).allSatisfy(incident -> assertThat(incident.getAffectedServices())
                .hasSize(1));
    }

    private void publish(SloBreachEvent event) {
        kafka.send(Topics.SLO_BREACH, event.serviceName(), event);
    }

    private Incident awaitSingleIncident() {
        await().atMost(AWAIT_TIMEOUT)
                .untilAsserted(() -> assertThat(incidents.findAll()).hasSize(1));
        return incidents.findAll().get(0);
    }
}
