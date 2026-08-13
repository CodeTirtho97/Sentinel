package com.sentinel.slo.math;

import com.sentinel.slo.domain.Window;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Multi-window, multi-burn-rate SLO evaluation.
 *
 * <p>Pure: no Spring, no I/O, no clock, no randomness. Takes numbers, returns a result.
 */
public final class BurnRateCalculator {

    public static final long DEFAULT_MINIMUM_EVENTS = 60L;
    public static final double DEFAULT_MINIMUM_COVERAGE = 0.75;

    /** Burn rates are order 1 to 100, so this is far below any meaningful difference. */
    private static final double EPSILON = 1e-9;

    private final List<Window> windows;
    private final long minimumEvents;
    private final double minimumCoverage;

    public BurnRateCalculator(Collection<Window> windows) {
        this(windows, DEFAULT_MINIMUM_EVENTS, DEFAULT_MINIMUM_COVERAGE);
    }

    public BurnRateCalculator(Collection<Window> windows, long minimumEvents, double minimumCoverage) {
        Objects.requireNonNull(windows, "windows");
        if (windows.isEmpty()) {
            throw new IllegalArgumentException("at least one window is required");
        }
        List<Window> sorted = new ArrayList<>(windows);
        // Most urgent first, so the first breach found is the highest severity.
        sorted.sort(Comparator.comparingInt((Window w) -> w.severity().rank()).reversed());
        this.windows = List.copyOf(sorted);
        this.minimumEvents = minimumEvents;
        this.minimumCoverage = minimumCoverage;
    }

    public static double errorBudget(double objective) {
        requireValidObjective(objective);
        return 1.0 - objective;
    }

    public static double burnRate(double errorRatio, double objective) {
        return errorRatio / errorBudget(objective);
    }

    /**
     * Evaluates every configured severity against the supplied per-window samples.
     *
     * @param samples observed ratios keyed by window duration; missing entries skip that severity
     */
    public BurnRateResult evaluate(double objective, Map<Duration, WindowSample> samples) {
        requireValidObjective(objective);
        Objects.requireNonNull(samples, "samples");

        double budget = 1.0 - objective;
        boolean anyUsable = false;
        double okLongBurn = 0.0;
        double okShortBurn = 0.0;

        for (Window window : windows) {
            WindowSample longSample = samples.get(window.longWindow());
            WindowSample shortSample = samples.get(window.shortWindow());
            if (longSample == null || shortSample == null) {
                continue;
            }
            if (!longSample.isUsable(minimumEvents, minimumCoverage)
                    || !shortSample.isUsable(minimumEvents, minimumCoverage)) {
                continue;
            }

            double longBurn = longSample.errorRatio() / budget;
            double shortBurn = shortSample.errorRatio() / budget;

            if (!anyUsable) {
                anyUsable = true;
                okLongBurn = longBurn;
                okShortBurn = shortBurn;
            }

            // Both windows must exceed: the short one stops alerts firing after recovery.
            if (exceeds(longBurn, window.burnThreshold()) && exceeds(shortBurn, window.burnThreshold())) {
                return new BurnRateResult.Breach(window.severity(), longBurn, shortBurn);
            }
        }

        if (!anyUsable) {
            return new BurnRateResult.InsufficientData("no window had a usable sample (need at least " + minimumEvents
                    + " events and " + minimumCoverage + " coverage)");
        }
        return new BurnRateResult.Ok(okLongBurn, okShortBurn);
    }

    private static boolean exceeds(double burn, double threshold) {
        return burn >= threshold - EPSILON;
    }

    private static void requireValidObjective(double objective) {
        if (!Double.isFinite(objective) || objective <= 0.0 || objective >= 1.0) {
            throw new IllegalArgumentException(
                    "objective must be in (0,1); 1.0 leaves a zero error budget: " + objective);
        }
    }
}
