package com.sentinel.slo.domain;

import java.time.Duration;
import java.util.UUID;

/** The domain and API type. Persistence uses a separate entity. */
public record SloDefinition(
        UUID id,
        String serviceName,
        SloType type,
        double objective,
        Integer latencyThresholdMs,
        Duration rollingWindow,
        boolean enabled) {}
