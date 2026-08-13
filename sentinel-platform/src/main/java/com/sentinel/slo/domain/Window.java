package com.sentinel.slo.domain;

import java.time.Duration;
import java.util.Objects;

/**
 * One row of the multi-window burn rate table: a severity fires only when both its long and short
 * windows exceed {@code burnThreshold}.
 */
public record Window(Severity severity, Duration longWindow, Duration shortWindow, double burnThreshold) {

    public Window {
        Objects.requireNonNull(severity, "severity");
        Objects.requireNonNull(longWindow, "longWindow");
        Objects.requireNonNull(shortWindow, "shortWindow");
        if (burnThreshold <= 0 || !Double.isFinite(burnThreshold)) {
            throw new IllegalArgumentException("burnThreshold must be positive and finite: " + burnThreshold);
        }
        if (longWindow.isZero() || longWindow.isNegative() || shortWindow.isZero() || shortWindow.isNegative()) {
            throw new IllegalArgumentException("windows must be positive durations");
        }
        // The short window exists to stop alerts firing long after recovery; equal or longer
        // than the long window would defeat that.
        if (shortWindow.compareTo(longWindow) >= 0) {
            throw new IllegalArgumentException(
                    "shortWindow (" + shortWindow + ") must be shorter than longWindow (" + longWindow + ")");
        }
    }
}
