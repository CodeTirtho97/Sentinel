package com.sentinel.correlation;

import com.sentinel.incident.api.IncidentResponses;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The fleet and the topology correlation reasons over. Read-only — the graph comes from config. */
@RestController
@RequestMapping("/api/v1/services")
public class ServiceGraphController {

    private final DependencyGraph graph;

    ServiceGraphController(DependencyGraph graph) {
        this.graph = graph;
    }

    @GetMapping
    public IncidentResponses.ServiceGraph get() {
        Map<String, Set<String>> edges = graph.edges();

        List<IncidentResponses.ServiceNode> nodes = edges.keySet().stream()
                .map(name -> new IncidentResponses.ServiceNode(name, graph.depthOf(name)))
                .toList();

        List<IncidentResponses.Edge> flattened = new ArrayList<>();
        edges.forEach((from, tos) -> tos.forEach(to -> flattened.add(new IncidentResponses.Edge(from, to))));

        return new IncidentResponses.ServiceGraph(nodes, flattened);
    }
}
