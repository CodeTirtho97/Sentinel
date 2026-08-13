package com.sentinel.rca;

import com.sentinel.correlation.DedupeStore;
import com.sentinel.events.IncidentEvent;
import com.sentinel.events.Topics;
import com.sentinel.incident.IncidentNotFoundException;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

/**
 * Drafts an RCA when an incident opens.
 *
 * <p>This listener is the reason the system is event-driven rather than a chain of method calls. A
 * ten-second model call sitting inside the evaluation path would eat most of a 15s cycle; here it
 * happens on its own consumer, on its own thread, and the evaluator never learns it took place.
 *
 * <p>Same contract as {@code BreachConsumer}: dedupe, work, mark, ack — in that order, marking only
 * after the work has committed.
 */
@Component
public class RcaConsumer {

    private static final Logger log = LoggerFactory.getLogger(RcaConsumer.class);

    private final RcaService rca;
    private final DedupeStore dedupe;
    private final MeterRegistry registry;

    RcaConsumer(RcaService rca, DedupeStore dedupe, MeterRegistry registry) {
        this.rca = rca;
        this.dedupe = dedupe;
        this.registry = registry;
    }

    @KafkaListener(id = "rca-listener", topics = Topics.INCIDENT_OPENED, groupId = "sentinel-rca")
    public void onIncidentOpened(IncidentEvent.Opened event, Acknowledgment ack) {
        // The incident id doubles as the dedupe key: exactly one Opened event is published per
        // incident, so it is already unique and already deterministic.
        UUID key = event.incidentId();

        if (dedupe.alreadyProcessed(key)) {
            log.debug("RCA already drafted for incident {}", key);
            ack.acknowledge();
            return;
        }

        try {
            // A drafting failure that is not the model's fault — the database being down, say —
            // belongs on the DLT, so anything else propagates. Model failures never reach here: the
            // drafter absorbs them and returns the deterministic summary instead.
            rca.draftFor(event.incidentId(), false);

        } catch (IncidentNotFoundException e) {
            // The incident this event announced no longer exists. Retrying cannot bring the row
            // back, so the default three attempts with backoff would block the partition for seven
            // seconds and then dead-letter it anyway — and every later event on that partition
            // waits behind it. Dead-lettering is also wrong: the DLT is for messages that need a
            // human, and an event about a deleted incident needs nobody.
            //
            // Counted rather than silent, so "this keeps happening" is still visible.
            log.warn("incident {} no longer exists; nothing to draft", key);
            registry.counter("sentinel.rca.orphaned").increment();
        }

        dedupe.markProcessed(key);
        ack.acknowledge();
    }
}
