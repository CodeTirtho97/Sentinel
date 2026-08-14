package com.sentinel.correlation;

import com.sentinel.config.SentinelProperties;
import com.sentinel.events.SloBreachEvent;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Turns one breach into the set of services breaking with it, and a guess at which broke first. */
@Component
public class Correlator {

    private static final Logger log = LoggerFactory.getLogger(Correlator.class);

    private final CorrelationStore store;
    private final DependencyGraph graph;
    private final MeterRegistry registry;
    private final Duration window;

    Correlator(CorrelationStore store, DependencyGraph graph, MeterRegistry registry, SentinelProperties props) {
        this.store = store;
        this.graph = graph;
        this.registry = registry;
        this.window = props.getCorrelation().getWindow();
    }

    public CorrelationResult correlate(SloBreachEvent event, Instant now) {
        store.record(event);

        List<BreachRef> recent = store.recentWithin(window, now);
        Set<String> breachedServices =
                recent.stream().map(BreachRef::serviceName).collect(Collectors.toCollection(LinkedHashSet::new));
        breachedServices.add(event.serviceName());

        Set<String> component = graph.componentOf(event.serviceName(), breachedServices);

        // Already ordered by breach time: the store returns the ZSET range in score order, and the
        // score is the earliest breach for that service.
        List<BreachRef> members =
                recent.stream().filter(b -> component.contains(b.serviceName())).toList();

        String origin = inferOrigin(members, event);

        // The money metric: how many raw alerts this incident is collapsing.
        registry.summary("sentinel.correlation.component.size").record(component.size());

        log.debug("correlated {} -> component {} origin {}", event.serviceName(), component, origin);
        return new CorrelationResult(component, origin, members);
    }

    /**
     * Earliest detection wins; a tie goes to the deeper service.
     *
     * <p>Deeper meaning further down the call chain: a dependency failing explains its callers
     * failing far more often than the reverse. This is a heuristic over a configured topology, not
     * causal inference, and is described as such.
     */
    private String inferOrigin(List<BreachRef> members, SloBreachEvent fallback) {
        return members.stream()
                .min(Comparator.comparing(BreachRef::detectedAt)
                        .thenComparing((BreachRef b) -> graph.depthOf(b.serviceName()), Comparator.reverseOrder())
                        // Last resort so two identical timestamps at equal depth still resolve the
                        // same way on every replay. Determinism matters more than the choice.
                        .thenComparing(BreachRef::serviceName))
                .map(BreachRef::serviceName)
                .orElseGet(fallback::serviceName);
    }
}
