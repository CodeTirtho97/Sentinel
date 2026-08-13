package com.sentinel.observability;

import static org.assertj.core.api.Assertions.assertThat;

import com.sentinel.support.AbstractIntegrationTest;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.health.Status;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * The Kubernetes probe contract, asserted where it is actually decided.
 *
 * <p>This suite runs a real Redpanda and no Prometheus at all, which is exactly the split the
 * readiness design cares about: consumers are assigned and working, the metrics source is
 * unreachable. That combination must produce an unready pod and a root health endpoint that still
 * answers 200 — anything else and a Prometheus outage would restart every replica.
 */
class ReadinessProbeIT extends AbstractIntegrationTest {

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private KafkaReadinessIndicator kafkaReadiness;

    @Autowired
    private MetricsSourceReadinessIndicator metricsReadiness;

    @Test
    @DisplayName("consumers report ready once the group has assigned them partitions")
    void kafkaReadyOnceAssigned() {
        // Assignment happens after the group joins, so this is genuinely eventual — which is the
        // whole reason the probe exists rather than trusting that a started process is a working one.
        Awaitility.await().atMost(AWAIT_TIMEOUT).untilAsserted(() -> assertThat(
                        kafkaReadiness.health().getStatus())
                .isEqualTo(Status.UP));

        assertThat(kafkaReadiness.health().getDetails())
                .containsKeys("breach-listener", "rca-listener", "audit-listener");
    }

    @Test
    @DisplayName("an unreachable metrics source is OUT_OF_SERVICE, not DOWN")
    void unreachableMetricsSourceIsOutOfService() {
        // There is no Prometheus in this suite. OUT_OF_SERVICE rather than DOWN is the distinction
        // between "stop routing to me" and "restart me".
        assertThat(metricsReadiness.health().getStatus()).isEqualTo(Status.OUT_OF_SERVICE);
    }

    @Test
    @DisplayName("readiness fails while the metrics source is unreachable")
    void readinessProbeIsUnready() {
        ResponseEntity<String> response = rest.getForEntity("/actuator/health/readiness", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody()).contains("metricsSource");
    }

    @Test
    @DisplayName("liveness stays up regardless — a dependency outage must not restart the pod")
    void livenessProbeStaysUp() {
        assertThat(rest.getForEntity("/actuator/health/liveness", String.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("the root health endpoint stays 200 while readiness is failing")
    void rootHealthIsNotAProbe() {
        // The Compose healthcheck and scripts/wait-for-health.sh watch this endpoint. If an
        // unreachable Prometheus made it 503, `make demo` would fail on a degraded dependency the
        // system is explicitly designed to survive.
        assertThat(rest.getForEntity("/actuator/health", String.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }
}
