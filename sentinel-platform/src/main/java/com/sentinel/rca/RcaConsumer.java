package com.sentinel.rca;

import com.sentinel.config.SentinelProperties;
import com.sentinel.correlation.DedupeStore;
import com.sentinel.events.IncidentEvent;
import com.sentinel.events.Topics;
import com.sentinel.incident.IncidentNotFoundException;
import com.sentinel.slo.domain.Severity;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Set;
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
    private final Set<Severity> draftSeverities;

    RcaConsumer(RcaService rca, DedupeStore dedupe, MeterRegistry registry, SentinelProperties props) {
        this.rca = rca;
        this.dedupe = dedupe;
        this.registry = registry;
        this.draftSeverities = Set.copyOf(props.getRca().getDraftSeverities());
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

        // Draft only for severities worth a model call.
        //
        // One LLM call per incident is fine for the demo's single cascade and ruinous for a storm:
        // measured, one run opened 7,632 incidents and attempted 7,632 drafts. Against a free tier
        // of roughly 30 requests a minute that exhausts the quota in seconds, opens the circuit
        // breaker, and every incident gets the deterministic fallback anyway — so the cost buys
        // nothing. On a paid endpoint it is simply a bill.
        //
        // Skipped incidents are not left blank: the RCA endpoint still renders the deterministic
        // timeline summary on demand, which is the same thing the fallback path produces.
        if (!draftSeverities.contains(event.severity())) {
            log.debug("skipping RCA for {} incident {}", event.severity(), key);
            registry.counter("sentinel.rca.skipped", "severity", event.severity().name())
                    .increment();
            dedupe.markProcessed(key);
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
