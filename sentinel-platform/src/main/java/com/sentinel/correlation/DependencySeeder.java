package com.sentinel.correlation;

import com.sentinel.config.SentinelProperties;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

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
        List<ServiceDependency> edges = new ArrayList<>();
        for (Map.Entry<String, List<String>> entry : props.getDependencies().entrySet()) {
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
        log.info("seeded {} dependency edges from configuration", edges.size());
    }
}
