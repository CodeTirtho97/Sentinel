package com.sentinel.correlation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.sentinel.events.SloBreachEvent;
import com.sentinel.events.Topics;
import com.sentinel.incident.Incident;
import com.sentinel.support.AbstractIntegrationTest;
import com.sentinel.support.Breaches;
import com.sentinel.support.MutableClock;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;

/**
 * The failure mode the compose stack actually exhibits, pinned as a test.
 *
 * <p>A cascade through synchronous calls does not arrive politely spread over cycles. Every service
 * in the chain fails the instant its dependency does, so all four cross the burn threshold in the
 * <b>same</b> evaluation cycle and carry the same detection timestamp.
 *
 * <p>That makes both halves of origin inference load-bearing at once: the timestamps genuinely tie,
 * so the answer has to come from graph depth, and the whole cycle has to be visible in the
 * correlation window before the first event is correlated, or whichever service is consumed first
 * opens an incident of one and freezes its key there.
 */
class SimultaneousCascadeIT extends AbstractIntegrationTest {

    @Autowired
    private KafkaTemplate<String, Object> kafka;

    @Autowired
    private CorrelationStore store;

    @Test
    @DisplayName("four services breaching in one cycle collapse to ONE incident, origin ledger-service")
    void simultaneousCascadeCollapsesToOneIncident() {
        Instant sameCycle = MutableClock.START;

        List<SloBreachEvent> cycle = List.of(
                // Deliberately listed with checkout first — the order the evaluator happens to visit
                // services in must not decide which one is named as the origin.
                Breaches.critical("checkout-service", sameCycle),
                Breaches.critical("cart-service", sameCycle),
                Breaches.critical("payment-service", sameCycle),
                Breaches.critical("ledger-service", sameCycle));

        // Stands in for the evaluator seeding the window as it publishes, so the first consumer to
        // look already sees the whole cycle rather than a component of one.
        cycle.forEach(store::record);
        cycle.forEach(event -> kafka.send(Topics.SLO_BREACH, event.serviceName(), event));

        await().atMost(AWAIT_TIMEOUT).untilAsserted(() -> {
            List<Incident> all = incidents.findAll();
            assertThat(all).hasSize(1);
            assertThat(all.get(0).getBreachCount()).isEqualTo(cycle.size());
        });

        Incident incident = incidents.findAll().get(0);
        assertThat(incident.getAffectedServices())
                .containsExactlyInAnyOrder("checkout-service", "cart-service", "payment-service", "ledger-service");

        // ledger-service is the deepest node in the call chain, which is the only signal available
        // once the timestamps tie. Naming checkout here would be naming the symptom.
        assertThat(incident.getCorrelationKey()).isEqualTo("ledger-service");
        assertThat(incident.getOriginService()).isEqualTo("ledger-service");
    }

    @Test
    @DisplayName("the deepest service wins the tie regardless of which one is consumed first")
    void originIsStableUnderConsumptionOrder() {
        Instant sameCycle = MutableClock.START;

        // Reverse order from the previous test. If the answer changed, origin inference would be
        // reporting arrival order rather than topology.
        List<SloBreachEvent> cycle = List.of(
                Breaches.critical("ledger-service", sameCycle),
                Breaches.critical("payment-service", sameCycle),
                Breaches.critical("cart-service", sameCycle),
                Breaches.critical("checkout-service", sameCycle));

        cycle.forEach(store::record);
        cycle.forEach(event -> kafka.send(Topics.SLO_BREACH, event.serviceName(), event));

        await().atMost(AWAIT_TIMEOUT).untilAsserted(() -> {
            List<Incident> all = incidents.findAll();
            assertThat(all).hasSize(1);
            assertThat(all.get(0).getBreachCount()).isEqualTo(cycle.size());
        });

        assertThat(incidents.findAll().get(0).getCorrelationKey()).isEqualTo("ledger-service");
    }
}
