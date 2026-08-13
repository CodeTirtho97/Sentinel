package com.sentinel.slo.metrics;

import com.fasterxml.jackson.databind.JsonNode;
import com.sentinel.config.SentinelProperties;
import io.github.resilience4j.retry.annotation.Retry;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Executes instant queries against the Prometheus HTTP API.
 *
 * <p>Separate from {@link PrometheusMetricsSource} so the Resilience4j retry actually applies:
 * annotations are proxy-based and a call from one method of a bean to another bypasses them.
 */
@Component
public class PrometheusQueryClient {

    private static final Logger log = LoggerFactory.getLogger(PrometheusQueryClient.class);

    private final RestClient client;
    private final String baseUrl;
    private final MeterRegistry registry;

    PrometheusQueryClient(RestClient prometheusRestClient, SentinelProperties props, MeterRegistry registry) {
        this.client = prometheusRestClient;
        this.baseUrl = props.getMetrics().getPrometheusBaseUrl();
        this.registry = registry;
    }

    /**
     * Runs an instant query and flattens the vector to {@code service -> value}.
     *
     * @return an empty map when the query matched nothing
     */
    @Retry(name = "prometheus")
    public Map<String, Double> queryVector(String expression) {
        Timer.Sample sample = Timer.start(registry);
        try {
            URI uri = URI.create(
                    baseUrl + "/api/v1/query?query=" + URLEncoder.encode(expression, StandardCharsets.UTF_8));
            JsonNode body = client.get().uri(uri).retrieve().body(JsonNode.class);
            Map<String, Double> values = parseVector(body);
            sample.stop(registry.timer("sentinel.metrics.query.duration", "outcome", "success"));
            return values;
        } catch (RuntimeException e) {
            sample.stop(registry.timer("sentinel.metrics.query.duration", "outcome", "failure"));
            registry.counter("sentinel.metrics.query.failures").increment();
            log.warn("Prometheus query failed [{}]: {}", expression, e.toString());
            throw e;
        }
    }

    public boolean isHealthy() {
        try {
            client.get().uri(URI.create(baseUrl + "/-/healthy")).retrieve().toBodilessEntity();
            return true;
        } catch (RuntimeException e) {
            log.debug("Prometheus health check failed: {}", e.toString());
            return false;
        }
    }

    private static Map<String, Double> parseVector(JsonNode body) {
        Map<String, Double> values = new HashMap<>();
        if (body == null || !"success".equals(body.path("status").asText())) {
            return values;
        }
        for (JsonNode result : body.path("data").path("result")) {
            String service = result.path("metric").path("service").asText(null);
            if (service == null || service.isBlank()) {
                continue;
            }
            // Instant query values arrive as [ <unix_time>, "<sample_value>" ].
            JsonNode value = result.path("value");
            if (!value.isArray() || value.size() < 2) {
                continue;
            }
            double parsed = value.get(1).asDouble(Double.NaN);
            if (Double.isFinite(parsed)) {
                values.put(service, parsed);
            }
        }
        return values;
    }
}
