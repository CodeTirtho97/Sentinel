package com.sentinel.correlation;

import com.sentinel.config.SentinelProperties;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;

/**
 * Mirrors {@code sentinel.dependencies} into the database at startup.
 *
 * <p>The table is the read model the graph walk and the {@code /services} endpoint use; YAML is the
 * source of truth. Rewritten rather than merged on every boot so removing an edge from the config
 * actually removes it.
 */
@Component
public class DependencySeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DependencySeeder.class);

    private final ServiceDependencyRepository repository;
    private final DependencyGraph graph;
    private final SentinelProperties props;

    DependencySeeder(ServiceDependencyRepository repository, DependencyGraph graph, SentinelProperties props) {
        this.repository = repository;
        this.graph = graph;
        this.props = props;
    }

    @Override
    @Transactional
    public void run(org.springframework.boot.ApplicationArguments args) {
        Map<String, List<String>> topology = new LinkedHashMap<>(props.getDependencies());
        int configured = topology.size();

        // Load-test only. The synthetic fleet is generated at runtime from SYNTHETIC_SERVICES, so
        // its chain edges cannot be written into YAML without pinning the fleet size in advance.
        // Fetching them means correlation has a graph to walk; without it every synthetic service
        // is an isolated node and the collapse ratio is 1:1 by construction.
        int fetched = 0;
        String url = props.getSyntheticTopologyUrl();
        if (url != null && !url.isBlank()) {
            Map<String, List<String>> synthetic = fetchTopology(url);
            topology.putAll(synthetic);
            fetched = synthetic.size();
        }

        List<ServiceDependency> edges = new ArrayList<>();
        for (Map.Entry<String, List<String>> entry : topology.entrySet()) {
            String service = entry.getKey();
            for (String dependsOn : entry.getValue()) {
                if (service.equals(dependsOn)) {
                    log.warn("ignoring self-dependency on {}", service);
                    continue;
                }
                edges.add(new ServiceDependency(service, dependsOn));
            }
        }

        repository.deleteAllInBatch();
        repository.saveAll(edges);
        repository.flush();

        graph.refresh();
        log.info(
                "seeded {} dependency edges across {} services ({} configured, {} fetched)",
                edges.size(),
                topology.size(),
                configured,
                fetched);
    }

    /**
     * A failure here is fatal on purpose.
     *
     * <p>Starting anyway would leave the synthetic fleet as isolated nodes, and the load test would
     * then produce a confident, plausible, and completely wrong 1:1 alert-collapse ratio. A run that
     * refuses to start is cheap; a measurement that silently means nothing is not.
     */
    private Map<String, List<String>> fetchTopology(String url) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, List<String>> body = RestClient.create().get().uri(url).retrieve().body(Map.class);
            if (body == null || body.isEmpty()) {
                throw new IllegalStateException("empty topology from " + url);
            }
            return body;
        } catch (RuntimeException e) {
            throw new IllegalStateException(
                    "sentinel.synthetic-topology-url is set to " + url
                            + " but the topology could not be fetched; refusing to start with an"
                            + " unconnected synthetic fleet",
                    e);
        }
    }
}
