package com.sentinel.slo.metrics;

import java.time.Instant;

/**
 * @param ratio bad events over total events for the window
 * @param totalEvents events observed in the window
 * @param coverage fraction of the window that has data, in {@code [0,1]}
 */
public record ErrorRatio(double ratio, long totalEvents, double coverage, Instant asOf) {}
