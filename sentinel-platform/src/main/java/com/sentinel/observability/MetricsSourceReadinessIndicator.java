package com.sentinel.observability;

import com.sentinel.slo.metrics.MetricsSource;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.actuate.health.Status;
import org.springframework.stereotype.Component;

/**
 * The other half of readiness: an evaluator that cannot reach its metrics store evaluates nothing.
 *
 * <p>It does not evaluate them <em>wrongly</em> — an unreachable source yields {@code
 * InsufficientData}, never a breach, so Sentinel failing can never manufacture an incident. But a
 * replica in that state is not doing the job either, and during a rollout Kubernetes should hold
 * traffic off it rather than count it as a healthy member of the Deployment.
 *
 * <p>Unready, not down. The incident API, the timeline and the RCA that Postgres already holds are
 * all still serveable, so the root health endpoint stays 200 and the container is not restarted —
 * see the status mapping in {@code application.yml}. Restarting on a Prometheus outage would be the
 * observability platform amplifying the outage it exists to observe.
 *
 * <p>Registered as {@code metricsSource}, the name the readiness group includes.
 */
@Component("metricsSource")
public class MetricsSourceReadinessIndicator implements HealthIndicator {

    private final MetricsSource metrics;

    MetricsSourceReadinessIndicator(MetricsSource metrics) {
        this.metrics = metrics;
    }

    @Override
    public Health health() {
        // One cheap GET per probe. The probe interval is 10s and the client carries its own timeout,
        // so a hung Prometheus fails this check rather than hanging the probe.
        return metrics.isHealthy()
                ? Health.up().build()
                : Health.status(Status.OUT_OF_SERVICE)
                        .withDetail("reason", "metrics source unreachable")
                        .build();
    }
}
