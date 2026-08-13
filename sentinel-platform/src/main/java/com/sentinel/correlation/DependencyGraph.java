package com.sentinel.correlation;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The static service topology, and the component walk that turns a set of simultaneous breaches into
 * one incident.
 *
 * <p>The graph is configured, not discovered. That is a stated limitation rather than a gap: this is
 * time-window plus static-topology correlation, not causal inference.
 */
@Component
public class DependencyGraph {

    private static final Logger log = LoggerFactory.getLogger(DependencyGraph.class);

    private final ServiceDependencyRepository repository;

    /** Snapshot replaced wholesale on refresh, so readers never see a half-built graph. */
    private volatile Snapshot snapshot;

    DependencyGraph(ServiceDependencyRepository repository) {
        this.repository = repository;
    }

    private record Snapshot(
            Map<String, Set<String>> callees, Map<String, Set<String>> callers, Map<String, Integer> depth) {}

    /** Rebuilt from the database. Called by the seeder at startup and available to tests. */
    @Transactional(readOnly = true)
    public void refresh() {
        Map<String, Set<String>> callees = new TreeMap<>();
        Map<String, Set<String>> callers = new TreeMap<>();

        for (ServiceDependency edge : repository.findAll()) {
            callees.computeIfAbsent(edge.getServiceName(), k -> new TreeSet<>()).add(edge.getDependsOn());
            callers.computeIfAbsent(edge.getDependsOn(), k -> new TreeSet<>()).add(edge.getServiceName());
            callees.computeIfAbsent(edge.getDependsOn(), k -> new TreeSet<>());
            callers.computeIfAbsent(edge.getServiceName(), k -> new TreeSet<>());
        }

        this.snapshot = new Snapshot(
                Collections.unmodifiableMap(callees),
                Collections.unmodifiableMap(callers),
                Collections.unmodifiableMap(computeDepths(callers)));
        log.info("dependency graph loaded: {} services, {} edges", callees.size(), edgeCount(callees));
    }

    private Snapshot current() {
        Snapshot local = snapshot;
        if (local == null) {
            synchronized (this) {
                if (snapshot == null) {
                    refresh();
                }
                local = snapshot;
            }
        }
        return local;
    }

    /**
     * The weakly connected component containing {@code service}, over the subgraph <b>induced by the
     * breached services only</b>.
     *
     * <p>Inducing on the breached set is what makes correlation do real work. Walking the full static
     * graph would make the entire demo fleet one permanent component, so any breach anywhere would
     * collapse into a single incident and the "two unconnected breaches produce two incidents" case
     * could never hold.
     *
     * <p>Direction is ignored — a breach propagates upward from a dependency to its callers, and the
     * incident should contain both ends of that edge.
     */
    public Set<String> componentOf(String service, Set<String> breachedServices) {
        Snapshot graph = current();
        if (!breachedServices.contains(service)) {
            // Defensive: the triggering service is always a member of its own component.
            breachedServices = new HashSet<>(breachedServices);
            breachedServices.add(service);
        }

        Set<String> component = new LinkedHashSet<>();
        Deque<String> queue = new ArrayDeque<>();
        queue.add(service);
        component.add(service);

        while (!queue.isEmpty()) {
            String node = queue.poll();
            for (String neighbour : neighbours(graph, node)) {
                if (breachedServices.contains(neighbour) && component.add(neighbour)) {
                    queue.add(neighbour);
                }
            }
        }
        return component;
    }

    private static Set<String> neighbours(Snapshot graph, String node) {
        Set<String> out = new TreeSet<>(graph.callees().getOrDefault(node, Set.of()));
        out.addAll(graph.callers().getOrDefault(node, Set.of()));
        return out;
    }

    /**
     * How far down the call chain a service sits: 0 for a service nothing calls, and one more than
     * its deepest caller otherwise.
     *
     * <p>Used only to break ties when two breaches share a detection timestamp. Deeper is treated as
     * more likely to be the origin, because a dependency failing explains its callers failing far
     * more often than the reverse.
     */
    public int depthOf(String service) {
        return current().depth().getOrDefault(service, 0);
    }

    /** The full configured topology, for the {@code GET /services} endpoint. */
    public Map<String, Set<String>> edges() {
        return current().callees();
    }

    private static Map<String, Integer> computeDepths(Map<String, Set<String>> callers) {
        Map<String, Integer> depths = new HashMap<>();
        for (String service : callers.keySet()) {
            depth(service, callers, depths, new LinkedHashSet<>());
        }
        return depths;
    }

    private static int depth(
            String service, Map<String, Set<String>> callers, Map<String, Integer> memo, Set<String> inProgress) {
        Integer cached = memo.get(service);
        if (cached != null) {
            return cached;
        }
        // A cycle in the topology is a configuration mistake, not something to crash on. Treating the
        // revisit as depth 0 keeps the walk terminating and the tie-break merely arbitrary.
        if (!inProgress.add(service)) {
            return 0;
        }

        int deepest = 0;
        for (String caller : callers.getOrDefault(service, Set.of())) {
            deepest = Math.max(deepest, depth(caller, callers, memo, inProgress) + 1);
        }

        inProgress.remove(service);
        memo.put(service, deepest);
        return deepest;
    }

    private static int edgeCount(Map<String, Set<String>> callees) {
        return callees.values().stream().mapToInt(Set::size).sum();
    }

    /** Test seam for the pure walk: build a graph without a database. */
    static DependencyGraph of(Map<String, List<String>> topology) {
        var graph = new DependencyGraph(null);
        Map<String, Set<String>> callees = new TreeMap<>();
        Map<String, Set<String>> callers = new TreeMap<>();
        topology.forEach((service, deps) -> {
            callees.computeIfAbsent(service, k -> new TreeSet<>());
            callers.computeIfAbsent(service, k -> new TreeSet<>());
            for (String dep : deps) {
                callees.get(service).add(dep);
                callers.computeIfAbsent(dep, k -> new TreeSet<>()).add(service);
                callees.computeIfAbsent(dep, k -> new TreeSet<>());
            }
        });
        graph.snapshot = new Snapshot(callees, callers, computeDepths(callers));
        return graph;
    }
}
