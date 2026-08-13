package com.sentinel.slo.math;

/** Error budget consumption over the rolling window, independent of burn rate. */
public final class ErrorBudgetCalculator {

    private ErrorBudgetCalculator() {}

    /**
     * Fraction of the error budget still available, clamped to {@code [0,1]}.
     *
     * <p>{@code remaining = 1 - errors / (total * errorBudget)}
     *
     * @return 1.0 when the window saw no traffic, since no budget can have been spent
     */
    public static double remaining(long errorsInWindow, long totalInWindow, double objective) {
        double budget = BurnRateCalculator.errorBudget(objective);
        if (errorsInWindow < 0 || totalInWindow < 0) {
            throw new IllegalArgumentException("event counts cannot be negative");
        }
        if (totalInWindow == 0) {
            return 1.0;
        }
        double allowedErrors = totalInWindow * budget;
        double consumed = errorsInWindow / allowedErrors;
        return Math.clamp(1.0 - consumed, 0.0, 1.0);
    }
}
