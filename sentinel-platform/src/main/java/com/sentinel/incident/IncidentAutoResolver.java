package com.sentinel.incident;

import com.sentinel.config.SentinelProperties;
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
 */
@Component
public class IncidentAutoResolver {

    private static final Logger log = LoggerFactory.getLogger(IncidentAutoResolver.class);

    private final IncidentRepository repository;
    private final IncidentService incidents;
    private final Clock clock;
    private final Duration autoResolveAfter;

    IncidentAutoResolver(
            IncidentRepository repository, IncidentService incidents, Clock clock, SentinelProperties props) {
        this.repository = repository;
        this.incidents = incidents;
        this.clock = clock;
        this.autoResolveAfter = props.getCorrelation().getAutoResolveAfter();
    }

    @Scheduled(fixedDelayString = "${sentinel.correlation.auto-resolve-scan-interval:60s}")
    public void resolveQuietIncidents() {
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
