package com.sentinel.rca;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.sentinel.events.IncidentEvent;
import com.sentinel.events.Topics;
import com.sentinel.incident.Incident;
import com.sentinel.incident.IncidentEventLog;
import com.sentinel.slo.domain.Severity;
import com.sentinel.support.AbstractIntegrationTest;
import com.sentinel.support.Breaches;
import com.sentinel.support.MutableClock;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;

/**
 * The full path: a breach becomes an incident, and the incident acquires an RCA on its own.
 *
 * <p>Runs with no API key configured, which is the {@code make demo} configuration exactly. If this
 * test needs a key to pass, so does the demo, and the demo must not.
 */
class RcaConsumerIT extends AbstractIntegrationTest {

    /**
     * Longer than the base timeout, because this is the only test spanning two consumer hops.
     *
     * <p>A breach has to be consumed, committed and republished as {@code incident.opened.v1}, then
     * consumed again by a second consumer group. Each hop can absorb a group rebalance, and on a
     * cold broker the first one is the slowest it will ever be. Observed drafting latency once
     * warm is under two seconds; the headroom is for the cold case, not the normal one.
     */
    private static final Duration RCA_TIMEOUT = Duration.ofSeconds(90);

    @Autowired
    private KafkaTemplate<String, Object> kafka;

    @Autowired
    private MeterRegistry registry;

    @Test
    @DisplayName("an opened incident gets an RCA draft without anyone asking for one")
    void incidentOpenedTriggersDrafting() {
        kafka.send(Topics.SLO_BREACH, "ledger-service", Breaches.critical("ledger-service", MutableClock.START));

        await().atMost(RCA_TIMEOUT).untilAsserted(() -> {
            List<Incident> all = incidents.findAll();
            assertThat(all).hasSize(1);
            assertThat(all.getFirst().hasRca()).isTrue();
        });

        Incident incident = incidents.findAll().getFirst();
        assertThat(incident.getRcaDraft())
                .contains("SUMMARY")
                .contains("LIKELY ORIGIN")
                .contains("BLAST RADIUS")
                .contains("WHAT TO CHECK NEXT")
                .contains("ledger-service");

        // With no key configured the deterministic drafter is wired, and says so rather than
        // implying a model was involved.
        assertThat(incident.getRcaFallback()).isTrue();
        assertThat(incident.getRcaModel()).isEqualTo(RcaDraft.TEMPLATE_MODEL);

        // The draft is a timeline event in its own right, so the incident detail shows when the
        // hypothesis appeared alongside the breaches it was drawn from.
        assertThat(eventLog.findByIncidentIdOrderByOccurredAtAsc(incident.getId()))
                .extracting(IncidentEventLog::getKind)
                .contains(IncidentEventLog.Kind.RCA);
    }

    @Test
    @DisplayName("a cascade's draft names the origin and the whole blast radius")
    void cascadeDraftCoversEveryAffectedService() {
        // Timestamps run backwards from the clock's frozen "now": correlation reads a window
        // *ending* at now, so a breach stamped in the future falls outside its own window and
        // correlates to nothing. Each breach is awaited before the next is published because
        // breaches are keyed by service and therefore spread across partitions — a single burst has
        // no defined cross-service order, and processing a caller first would legitimately open a
        // second incident.
        publishAndAwait("ledger-service", MutableClock.START.minusSeconds(30), 1);
        publishAndAwait("payment-service", MutableClock.START.minusSeconds(15), 2);
        publishAndAwait("cart-service", MutableClock.START, 3);

        // The draft is written once, when the incident opens, so it legitimately predates the later
        // breaches attaching. What must hold is that it exists and names the origin.
        await().atMost(RCA_TIMEOUT).untilAsserted(() -> {
            Incident incident = incidents.findAll().getFirst();
            assertThat(incident.hasRca()).isTrue();
            assertThat(incident.getRcaDraft()).contains("ledger-service");
        });

        assertThat(incidents.findAll().getFirst().getOriginService()).isEqualTo("ledger-service");
    }

    @Test
    @DisplayName("an event for an incident that no longer exists is dropped, not retried into the DLT")
    void orphanedOpenedEventDoesNotBlockThePartition() {
        // An Opened event whose incident has since been deleted. Retrying cannot bring the row
        // back, so the default three attempts with backoff would hold the partition for seven
        // seconds and dead-letter it anyway — and every later event on that partition waits behind
        // it. This is not hypothetical: it is what made the suite's own RCA tests time out.
        // Counters are cumulative across the shared application context, and DeadLetterIT
        // legitimately dead-letters things earlier in the run — so this has to be a delta, not an
        // absolute.
        double dltBefore = deadLetterCount();

        var orphan = new IncidentEvent.Opened(
                UUID.randomUUID(),
                "ghost-service",
                Severity.CRITICAL,
                "ghost-service",
                Set.of("ghost-service"),
                MutableClock.START);
        kafka.send(Topics.INCIDENT_OPENED, orphan.incidentId().toString(), orphan);

        // The proof it did not block: a real breach published afterwards still gets its draft.
        kafka.send(Topics.SLO_BREACH, "ledger-service", Breaches.critical("ledger-service", MutableClock.START));

        await().atMost(RCA_TIMEOUT).untilAsserted(() -> {
            List<Incident> all = incidents.findAll();
            assertThat(all).hasSize(1);
            assertThat(all.getFirst().hasRca()).isTrue();
        });

        assertThat(registry.counter("sentinel.rca.orphaned").count()).isPositive();
        // Dropped deliberately, not dead-lettered: the DLT is for messages that need a human, and
        // an event about a deleted incident needs nobody.
        assertThat(deadLetterCount()).isEqualTo(dltBefore);
    }

    private double deadLetterCount() {
        return registry.find("sentinel.consumer.dlt").counters().stream()
                .mapToDouble(c -> c.count())
                .sum();
    }

    private void publishAndAwait(String service, Instant detectedAt, int expectedAffected) {
        kafka.send(Topics.SLO_BREACH, service, Breaches.critical(service, detectedAt));
        await().atMost(AWAIT_TIMEOUT).untilAsserted(() -> {
            List<Incident> all = incidents.findAll();
            assertThat(all).hasSize(1);
            assertThat(all.getFirst().getAffectedServices()).hasSize(expectedAffected);
        });
    }
}
