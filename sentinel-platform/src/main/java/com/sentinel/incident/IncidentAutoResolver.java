package com.sentinel.incident;

import com.sentinel.config.SentinelProperties;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Closes incidents whose members have gone quiet.
 *
 * <p>Reads the clock through the injected {@code Clock} bean rather than {@code Instant.now()}, so
 * the auto-resolve test advances time instead of sleeping for ten minutes.
 *
 * <p><b>Only runs while the breach consumer is current.</b> The sweep infers "the problem stopped"
 * from "no breach arrived recently", and that inference is a lie whenever the consumer is behind:
 * the breaches exist, they are sitting in the topic, and closing incidents on the strength of not
 * having read them yet turns a storm into a silent all-clear. Absence of evidence is not evidence
 * of absence, and for an alerting product that distinction is the whole job.
 */
@Component
public class IncidentAutoResolver {

    private static final Logger log = LoggerFactory.getLogger(IncidentAutoResolver.class);

    /**
     * Kafka's client metric. The topic tag arrives as {@code slo.breach.v1} from some binders and
     * {@code slo_breach_v1} from others, so it is normalised before comparison rather than matched
     * exactly — an exact match that silently misses would disable the guard and restore the bug.
     */
    private static final String LAG_METRIC = "kafka.consumer.fetch.manager.records.lag";

    private final IncidentRepository repository;
    private final IncidentService incidents;
    private final Clock clock;
    private final Duration autoResolveAfter;
    private final MeterRegistry registry;
    private final long maxLag;

    IncidentAutoResolver(
            IncidentRepository repository,
            IncidentService incidents,
            Clock clock,
            SentinelProperties props,
            MeterRegistry registry) {
        this.repository = repository;
        this.incidents = incidents;
        this.clock = clock;
        this.autoResolveAfter = props.getCorrelation().getAutoResolveAfter();
        this.registry = registry;
        this.maxLag = props.getCorrelation().getAutoResolveMaxLag();
    }

    /**
     * Peak backlog across the breach topic's partitions.
     *
     * <p>Returns 0 when the metric is absent — the in-memory publisher used by tests registers no
     * Kafka client metrics, and a missing gauge must not be read as "infinitely behind" or the
     * sweep would never run at all.
     */
    private long breachConsumerLag() {
        return (long) registry.find(LAG_METRIC).gauges().stream()
                .filter(g -> {
                    String topic = g.getId().getTag("topic");
                    return topic != null && topic.replace('_', '.').startsWith("slo.breach");
                })
                .mapToDouble(Gauge::value)
                .filter(v -> !Double.isNaN(v))
                .max()
                .orElse(0.0);
    }

    @Scheduled(fixedDelayString = "${sentinel.correlation.auto-resolve-scan-interval:60s}")
    public void resolveQuietIncidents() {
        // Refuse to draw conclusions from data known to be stale. A backlog means breaches exist
        // that this process has not read; any one of them could belong to an incident about to be
        // closed. Skipping leaves incidents open for longer than ideal, which is noisy and obvious.
        // Not skipping closes live incidents mid-outage, which is quiet and catastrophic.
        long lag = breachConsumerLag();
        if (lag > maxLag) {
            registry.counter("sentinel.incidents.autoresolve.skipped").increment();
            log.warn(
                    "skipping auto-resolve sweep: breach consumer is {} messages behind (limit {}); "
                            + "lastBreachAt cannot be trusted while the backlog drains",
                    lag,
                    maxLag);
            return;
        }

        Instant cutoff = clock.instant().minus(autoResolveAfter);
        List<Incident> stale = repository.findStaleUnresolved(cutoff);
        if (stale.isEmpty()) {
            return;
        }

        for (Incident incident : stale) {
            try {
                incidents.transition(incident.getId(), IncidentState.RESOLVED, "auto-resolve");
                log.info(
                        "auto-resolved incident {} (no breach since {})", incident.getId(), incident.getLastBreachAt());
            } catch (RuntimeException e) {
                // A breach landing between the scan and the transition can move the incident under
                // us. Losing that race is correct behaviour, not an error worth failing the sweep.
                log.debug("skipped auto-resolving {}: {}", incident.getId(), e.toString());
            }
        }
    }
}
