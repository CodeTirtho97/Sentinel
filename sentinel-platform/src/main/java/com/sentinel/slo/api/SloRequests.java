package com.sentinel.slo.api;

import com.sentinel.slo.domain.SloDefinition;
import com.sentinel.slo.domain.SloType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import java.util.UUID;

public final class SloRequests {

    private SloRequests() {}

    public record Create(
            @NotBlank String serviceName,
            @NotNull SloType type,
            double objective,
            Integer latencyThresholdMs,
            Duration rollingWindow) {}

    /** Null fields are left unchanged. */
    public record Patch(Boolean enabled, Double objective) {}

    public record Response(
            UUID id,
            String serviceName,
            SloType type,
            double objective,
            Integer latencyThresholdMs,
            String rollingWindow,
            boolean enabled) {

        public static Response from(SloDefinition slo) {
            return new Response(
                    slo.id(),
                    slo.serviceName(),
                    slo.type(),
                    slo.objective(),
                    slo.latencyThresholdMs(),
                    slo.rollingWindow().toString(),
                    slo.enabled());
        }
    }

    /**
     * @param budgetRemaining fraction of the error budget left, or null when there is no data
     */
    public record Budget(
            UUID id,
            String serviceName,
            SloType type,
            double objective,
            String rollingWindow,
            Double budgetRemaining,
            Double longBurnRate,
            Double shortBurnRate,
            String status) {}
}
