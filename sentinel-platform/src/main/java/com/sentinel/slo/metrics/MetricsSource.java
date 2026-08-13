package com.sentinel.slo.metrics;

import com.sentinel.slo.domain.SloType;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;

/**
 * The only seam that knows how metrics are stored. No PromQL string, no HTTP concept and no
 * Prometheus response type crosses this boundary.
 */
public interface MetricsSource {

    /**
     * Ratios for every service at once, keyed by service name.
     *
     * <p>Batched deliberately: at a thousand services this is one query rather than a thousand.
     *
     * @return an empty map when the window has no data; never null
     */
    Map<String, ErrorRatio> errorRatios(SloType type, Integer latencyThresholdMs, Duration window);

    /** Single service lookup. {@code Optional.empty()} means insufficient data, never a breach. */
    default Optional<ErrorRatio> errorRatio(
            String serviceName, SloType type, Integer latencyThresholdMs, Duration window) {
        return Optional.ofNullable(errorRatios(type, latencyThresholdMs, window).get(serviceName));
    }

    /** Raw counts over the error budget window, used for budget accounting rather than burn rate. */
    Optional<BudgetCounts> budgetCounts(String serviceName, Duration rollingWindow);

    /** Health of the backing store. Feeds the readiness probe in Phase 4. */
    boolean isHealthy();
}
