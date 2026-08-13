package com.sentinel.correlation;

import com.sentinel.events.SloBreachEvent;
import com.sentinel.events.Topics;
import com.sentinel.incident.IncidentService;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

/**
 * Breach → incident.
 *
 * <p>The ordering of the four steps is the whole design:
 *
 * <ol>
 *   <li>check the dedupe key — cheap skip of work already done
 *   <li>correlate and commit, in one database transaction
 *   <li>mark processed, only once that transaction has committed
 *   <li>acknowledge, only once the mark is set
 * </ol>
 *
 * <p>Marking before the commit would be faster and silently wrong: a commit failure after the mark
 * means Kafka redelivers, the mark is found, the consumer returns, and the breach vanishes with no
 * incident and no dead letter to show for it.
 */
@Component
public class BreachConsumer {

    private static final Logger log = LoggerFactory.getLogger(BreachConsumer.class);

    private final Correlator correlator;
    private final IncidentService incidents;
    private final DedupeStore dedupe;
    private final Clock clock;

    BreachConsumer(Correlator correlator, IncidentService incidents, DedupeStore dedupe, Clock clock) {
        this.correlator = correlator;
        this.incidents = incidents;
        this.dedupe = dedupe;
        this.clock = clock;
    }

    /** The container id is stable so it can be stopped and started for drain and rebalance testing. */
    @KafkaListener(id = "breach-listener", topics = Topics.SLO_BREACH, groupId = "sentinel-breach")
    public void onBreach(SloBreachEvent event, Acknowledgment ack) {
        if (dedupe.alreadyProcessed(event.eventId())) {
            log.debug("skipping already-processed breach {}", event.eventId());
            ack.acknowledge();
            return;
        }

        // Any exception escaping here is deliberate: it reaches DefaultErrorHandler, which retries
        // and then dead-letters. Swallowing it would lose the breach without a trace.
        var correlation = correlator.correlate(event, clock.instant());
        var outcome = incidents.openOrAttach(correlation, event);

        dedupe.markProcessed(event.eventId());
        ack.acknowledge();

        log.debug(
                "breach {} -> incident {} (created={}, duplicate={})",
                event.eventId(),
                outcome.incidentId(),
                outcome.created(),
                outcome.duplicate());
    }
}
