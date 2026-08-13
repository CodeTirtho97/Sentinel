package com.sentinel.observability;

import com.sentinel.incident.IncidentRepository;
import com.sentinel.slo.domain.Severity;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Publishes the active-incident gauge.
 *
 * <p>Refreshed on a schedule rather than read inside the gauge callback: Prometheus scrapes are not
 * something to hang a database query off, and a 15s-stale count of open incidents is no less useful
 * than a live one.
 */
@Component
public class IncidentMetrics {

    private static final Logger log = LoggerFactory.getLogger(IncidentMetrics.class);

    private final IncidentRepository repository;
    private final Map<Severity, AtomicLong> active = new EnumMap<>(Severity.class);

    IncidentMetrics(IncidentRepository repository, MeterRegistry registry) {
        this.repository = repository;
        for (Severity severity : Severity.values()) {
            AtomicLong holder = new AtomicLong();
            active.put(severity, holder);
            Gauge.builder("sentinel.incidents.active", holder, AtomicLong::doubleValue)
                    .description("Incidents not yet resolved")
                    .tag("severity", severity.name())
                    .register(registry);
        }
    }

    @Scheduled(fixedDelayString = "${sentinel.metrics.gauge-refresh-interval:15s}")
    @Transactional(readOnly = true)
    public void refresh() {
        try {
            Map<Severity, Long> counts = new EnumMap<>(Severity.class);
            for (Object[] row : repository.countActiveBySeverity()) {
                counts.put((Severity) row[0], (Long) row[1]);
            }
            active.forEach((severity, holder) -> holder.set(counts.getOrDefault(severity, 0L)));
        } catch (RuntimeException e) {
            // Instrumentation must never be the thing that takes the process down.
            log.warn("could not refresh active incident gauge: {}", e.toString());
        }
    }
}
