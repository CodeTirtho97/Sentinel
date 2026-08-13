package com.sentinel.correlation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.sentinel.events.SloBreachEvent;
import com.sentinel.events.Topics;
import com.sentinel.incident.Incident;
import com.sentinel.incident.IncidentEventLog;
import com.sentinel.support.AbstractIntegrationTest;
import com.sentinel.support.Breaches;
import com.sentinel.support.MutableClock;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.MessageListenerContainer;

/**
 * Scenario 8. Stopping the listener is the closest in-process equivalent of a pod eviction: the
 * offset stays uncommitted, the group rebalances on restart, and everything unacknowledged is
 * delivered again.
 */
class ConsumerRestartIT extends AbstractIntegrationTest {

    private static final String LISTENER_ID = "breach-listener";
    private static final int EVENTS = 20;

    @Autowired
    private KafkaTemplate<String, Object> kafka;

    @Autowired
    private KafkaListenerEndpointRegistry registry;

    @AfterEach
    void restartListener() {
        // A test that leaves the listener stopped fails every test that runs after it, with a
        // symptom that points nowhere near the cause.
        MessageListenerContainer container = breachContainer();
        if (!container.isRunning()) {
            container.start();
        }
    }

    /**
     * The claim under test is "no lost, no duplicated", and deliberately not "exactly one incident".
     *
     * <p>A backlog released after a restart arrives in whatever order the three partitions are
     * drained in, and keying by service name only guarantees ordering <i>within</i> a service. If
     * checkout is processed before ledger, checkout opens its own incident — the breached set at
     * that moment is {checkout} alone, and the correlation key is frozen at creation by design
     * (§7), so it cannot later be folded into ledger's.
     *
     * <p>That is the documented boundary of time-window correlation, not a defect, and asserting a
     * single incident here would be asserting a guarantee the design does not make. What must hold
     * regardless of ordering is that every breach is recorded exactly once and no service is lost.
     */
    @Test
    @DisplayName("events published while the consumer is down are all processed on restart, exactly once")
    void nothingIsLostOrDuplicatedAcrossARestart() {
        MessageListenerContainer container = breachContainer();
        container.stop();
        await().atMost(AWAIT_TIMEOUT).until(() -> !container.isRunning());

        List<SloBreachEvent> published = List.of(
                Breaches.critical("ledger-service", MutableClock.START.minusSeconds(45)),
                Breaches.critical("payment-service", MutableClock.START.minusSeconds(30)),
                Breaches.critical("cart-service", MutableClock.START.minusSeconds(15)),
                Breaches.critical("checkout-service", MutableClock.START));
        published.forEach(event -> kafka.send(Topics.SLO_BREACH, event.serviceName(), event));
        kafka.flush();

        assertThat(incidents.count()).isZero();

        container.start();
        await().atMost(AWAIT_TIMEOUT).until(container::isRunning);

        // Nothing lost: every published breach has a timeline entry.
        await().atMost(AWAIT_TIMEOUT)
                .untilAsserted(() -> assertThat(breachEntries()).hasSize(published.size()));

        // Nothing duplicated: still exactly that many after the stream has had time to redeliver.
        await().during(Duration.ofSeconds(3)).atMost(AWAIT_TIMEOUT).untilAsserted(() -> assertThat(breachEntries())
                .hasSize(published.size()));

        assertThat(breachEntries())
                .extracting(IncidentEventLog::getEventId)
                .doesNotHaveDuplicates()
                .containsExactlyInAnyOrderElementsOf(
                        published.stream().map(SloBreachEvent::eventId).toList());

        // Every breached service ended up accounted for by some incident.
        assertThat(incidents.findAll().stream()
                        .flatMap(incident -> incident.getAffectedServices().stream())
                        .distinct())
                .containsExactlyInAnyOrder("ledger-service", "payment-service", "cart-service", "checkout-service");

        assertThat(incidents.findAll().stream()
                        .mapToInt(Incident::getBreachCount)
                        .sum())
                .isEqualTo(published.size());
    }

    @Test
    @DisplayName("a mid-stream restart neither loses nor duplicates incidents")
    void restartMidStreamIsSafe() {
        for (int i = 0; i < EVENTS; i++) {
            SloBreachEvent event = Breaches.critical("ledger-service", MutableClock.START.minusSeconds(i * 15L));
            kafka.send(Topics.SLO_BREACH, event.serviceName(), event);
        }
        kafka.flush();

        MessageListenerContainer container = breachContainer();
        container.stop();
        container.start();
        await().atMost(AWAIT_TIMEOUT).until(container::isRunning);

        // All twenty events share one correlation key, so however the restart splits the stream the
        // answer is one incident with twenty distinct timeline entries.
        await().atMost(AWAIT_TIMEOUT).untilAsserted(() -> {
            assertThat(incidents.count()).isEqualTo(1);
            assertThat(breachEntries(incidents.findAll().get(0).getId())).hasSize(EVENTS);
        });

        await().during(Duration.ofSeconds(2)).atMost(AWAIT_TIMEOUT).untilAsserted(() -> assertThat(incidents.count())
                .isEqualTo(1));
    }

    private MessageListenerContainer breachContainer() {
        MessageListenerContainer container = registry.getListenerContainer(LISTENER_ID);
        assertThat(container).as("listener %s must exist", LISTENER_ID).isNotNull();
        return container;
    }
}
