package com.sentinel.incident;

import com.sentinel.events.IncidentEvent;
import com.sentinel.events.Topics;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

/**
 * Structured audit trail of every lifecycle change.
 *
 * <p>Deliberately thin. It is the first thing on the cut list, and if it goes the topic declaration
 * goes with it — a declared topic with nothing consuming it is a loose end.
 */
@Component
public class AuditConsumer {

    private static final Logger log = LoggerFactory.getLogger(AuditConsumer.class);

    private final MeterRegistry registry;

    AuditConsumer(MeterRegistry registry) {
        this.registry = registry;
    }

    @KafkaListener(id = "audit-listener", topics = Topics.INCIDENT_STATE_CHANGED, groupId = "sentinel-audit")
    public void onStateChanged(IncidentEvent.StateChanged event, Acknowledgment ack) {
        log.info(
                "AUDIT incident={} key={} {} -> {} by {} at {}",
                event.incidentId(),
                event.correlationKey(),
                event.from(),
                event.to(),
                event.actor(),
                event.changedAt());
        registry.counter("sentinel.incidents.transitions", "to", event.to()).increment();
        ack.acknowledge();
    }
}
