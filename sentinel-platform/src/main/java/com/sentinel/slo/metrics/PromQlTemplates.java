package com.sentinel.slo.metrics;

import com.sentinel.slo.domain.SloType;
import java.time.Duration;

/**
 * Fixed query templates. No user-supplied PromQL ever reaches Prometheus.
 *
 * <p>Every expression selects a precomputed recording rule, so a cycle costs a handful of instant
 * queries no matter how many services exist.
 */
public final class PromQlTemplates {

    private PromQlTemplates() {}

    /** Formats a duration the way the recording rule names do: 1m, 5m, 1h, 3d. */
    public static String windowLabel(Duration window) {
        long seconds = window.toSeconds();
        if (seconds <= 0) {
            throw new IllegalArgumentException("window must be positive: " + window);
        }
        if (seconds % 86_400 == 0) {
            return (seconds / 86_400) + "d";
        }
        if (seconds % 3_600 == 0) {
            return (seconds / 3_600) + "h";
        }
        if (seconds % 60 == 0) {
            return (seconds / 60) + "m";
        }
        return seconds + "s";
    }

    /** Micrometer publishes bucket boundaries in seconds, so 500ms is {@code le="0.5"}. */
    public static String bucketLabel(int latencyThresholdMs) {
        if (latencyThresholdMs <= 0) {
            throw new IllegalArgumentException("latency threshold must be positive: " + latencyThresholdMs);
        }
        return Double.toString(latencyThresholdMs / 1000.0);
    }

    public static String ratio(SloType type, Integer latencyThresholdMs, Duration window) {
        String label = windowLabel(window);
        return switch (type) {
            case AVAILABILITY -> "slo:error_ratio:" + label;
            case LATENCY -> {
                if (latencyThresholdMs == null) {
                    throw new IllegalArgumentException("LATENCY SLOs require a latency threshold");
                }
                yield "slo:latency_ratio:" + label + "{le=\"" + bucketLabel(latencyThresholdMs) + "\"}";
            }
        };
    }

    public static String requests(Duration window) {
        return "slo:requests:" + windowLabel(window);
    }

    /** Sample count, used to derive how much of the window actually has data. */
    public static String samples(Duration window) {
        return "slo:samples:" + windowLabel(window);
    }

    public static String budgetErrors(Duration window) {
        return "slo:errors:" + windowLabel(window);
    }

    public static String budgetTotal(Duration window) {
        return "slo:total:" + windowLabel(window);
    }
}
