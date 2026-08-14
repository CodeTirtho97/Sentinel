package com.sentinel.incident;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.sentinel.correlation.BreachRef;
import com.sentinel.correlation.CorrelationResult;
import com.sentinel.events.SloBreachEvent;
import com.sentinel.events.Topics;
import com.sentinel.support.AbstractIntegrationTest;
import com.sentinel.support.Breaches;
import com.sentinel.support.MutableClock;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;

/**
 * Scenario 2. The three idempotency layers are ordered, not redundant, and each is tested for what
 * only it can catch.
 */
class IncidentIdempotencyIT extends AbstractIntegrationTest {

    private static final int THREADS = 50;

    @Autowired
    private IncidentService service;

    @Autowired
    private KafkaTemplate<String, Object> kafka;

    @Test
    @DisplayName("50 concurrent breaches on one correlation key create exactly one incident")
    void concurrentBreachesCreateOneIncident() throws Exception {
        // Distinct event ids: this is the concurrency test, not the redelivery test. Every thread
        // has real work to do, so they all race on the partial unique index rather than being
        // filtered out early by dedupe.
        //
        // Spaced a full evaluation interval apart, because that is what makes them distinct. Event
        // ids are derived from (sloId, severity, interval bucket), so fifty breaches one second
        // apart would collapse into four ids and quietly test redelivery instead.
        List<SloBreachEvent> distinct = new java.util.ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            distinct.add(Breaches.critical(
                    "ledger-service", MutableClock.START.minusSeconds(i * Breaches.INTERVAL.toSeconds())));
        }

        var created = new AtomicInteger();
        var failures = new CopyOnWriteArrayList<Throwable>();
        runConcurrently(
                index -> {
                    var event = distinct.get(index);
                    var correlation = new CorrelationResult(Set.of("ledger-service"), "ledger-service", List.of(BreachRef.of(event)));
                    if (service.openOrAttach(correlation, event).created()) {
                        created.incrementAndGet();
                    }
                },
                failures);

        assertThat(failures).isEmpty();
        assertThat(incidents.count()).isEqualTo(1);
        // Exactly one caller may believe it opened the incident; the other 49 must have attached.
        assertThat(created.get()).isEqualTo(1);

        Incident incident = incidents.findAll().get(0);
        assertThat(breachEntries(incident.getId())).hasSize(THREADS);
        assertThat(incident.getBreachCount()).isEqualTo(THREADS);
    }

    @Test
    @DisplayName("50 concurrent deliveries of ONE event produce one incident and one timeline entry")
    void duplicateEventIsRecordedOnce() throws Exception {
        SloBreachEvent event = Breaches.critical("ledger-service", MutableClock.START);
        var correlation = new CorrelationResult(Set.of("ledger-service"), "ledger-service", List.of(BreachRef.of(event)));

        var duplicates = new AtomicInteger();
        var failures = new CopyOnWriteArrayList<Throwable>();
        runConcurrently(
                index -> {
                    if (service.openOrAttach(correlation, event).duplicate()) {
                        duplicates.incrementAndGet();
                    }
                },
                failures);

        assertThat(failures).isEmpty();
        assertThat(incidents.count()).isEqualTo(1);

        Incident incident = incidents.findAll().get(0);
        assertThat(breachEntries(incident.getId())).hasSize(1);
        // The count must not be inflated by 49 redeliveries of the same detection.
        assertThat(incident.getBreachCount()).isEqualTo(1);
        assertThat(duplicates.get()).isEqualTo(THREADS - 1);
    }

    @Test
    @DisplayName("replaying the same event through Kafka 50 times creates one incident")
    void duplicateKafkaDeliveryIsAbsorbed() {
        SloBreachEvent event = Breaches.critical("ledger-service", MutableClock.START);

        for (int i = 0; i < THREADS; i++) {
            kafka.send(Topics.SLO_BREACH, event.serviceName(), event);
        }

        await().atMost(AWAIT_TIMEOUT)
                .untilAsserted(() -> assertThat(incidents.count()).isEqualTo(1));

        Incident incident = incidents.findAll().get(0);
        // Held stable for a moment: an incorrect implementation opens the duplicates late rather
        // than never, and an assertion that fires on the first success would miss it.
        await().during(java.time.Duration.ofSeconds(2)).atMost(AWAIT_TIMEOUT).untilAsserted(() -> {
            assertThat(incidents.count()).isEqualTo(1);
            assertThat(breachEntries(incident.getId())).hasSize(1);
        });
    }

    @Test
    @DisplayName("a resolved incident does not block a new one under the same key")
    void resolvedIncidentFreesTheKey() {
        SloBreachEvent first = Breaches.critical("ledger-service", MutableClock.START.minusSeconds(60));
        var correlation = new CorrelationResult(Set.of("ledger-service"), "ledger-service", List.of(BreachRef.of(first)));

        var opened = service.openOrAttach(correlation, first);
        service.transition(opened.incidentId(), IncidentState.RESOLVED, "test");

        // This is why the unique index is partial. A total index on correlation_key would mean a
        // service that breaks twice in a day can never open a second incident.
        SloBreachEvent second = Breaches.critical("ledger-service", MutableClock.START);
        var reopened = service.openOrAttach(
                new CorrelationResult(Set.of("ledger-service"), "ledger-service", List.of(BreachRef.of(second))), second);

        assertThat(reopened.created()).isTrue();
        assertThat(reopened.incidentId()).isNotEqualTo(opened.incidentId());
        assertThat(incidents.count()).isEqualTo(2);
    }

    private void runConcurrently(ThreadBody body, List<Throwable> failures) throws Exception {
        var ready = new CountDownLatch(THREADS);
        var go = new CountDownLatch(1);
        var done = new CountDownLatch(THREADS);

        try (ExecutorService pool = Executors.newFixedThreadPool(THREADS)) {
            for (int i = 0; i < THREADS; i++) {
                final int index = i;
                pool.submit(() -> {
                    ready.countDown();
                    try {
                        // Every thread waits on one latch, so they contend for real rather than
                        // trickling in as the pool warms up.
                        go.await();
                        body.run(index);
                    } catch (Throwable t) {
                        failures.add(t);
                    } finally {
                        done.countDown();
                    }
                });
            }

            assertThat(ready.await(30, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            assertThat(done.await(60, TimeUnit.SECONDS)).isTrue();
        }
    }

    @FunctionalInterface
    private interface ThreadBody {
        void run(int index) throws Exception;
    }
}
