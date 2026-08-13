package com.sentinel.slo.math;

/**
 * What the metrics backend observed over one window.
 *
 * @param errorRatio bad events divided by total events, in {@code [0,1]}
 * @param totalEvents events seen in the window, used to reject statistically meaningless samples
 * @param coverage fraction of the window that actually has data, in {@code [0,1]}
 */
public record WindowSample(double errorRatio, long totalEvents, double coverage) {

    /** A sample with data but no traffic. */
    public static WindowSample empty() {
        return new WindowSample(0.0, 0L, 1.0);
    }

    /**
     * A sample is usable only if the ratio is a real number in range, enough events were seen to
     * be meaningful, and the window is mostly populated. Coverage is what distinguishes a genuinely
     * healthy service from one that started ten minutes into a one hour window.
     */
    public boolean isUsable(long minimumEvents, double minimumCoverage) {
        return Double.isFinite(errorRatio)
                && errorRatio >= 0.0
                && errorRatio <= 1.0
                && totalEvents >= minimumEvents
                && coverage >= minimumCoverage;
    }
}
