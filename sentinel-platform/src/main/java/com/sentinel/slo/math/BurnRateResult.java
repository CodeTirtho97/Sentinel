package com.sentinel.slo.math;

import com.sentinel.slo.domain.Severity;

public sealed interface BurnRateResult {

    /** Evaluated successfully, nothing tripped. Burns are from the most urgent usable window. */
    record Ok(double longBurn, double shortBurn) implements BurnRateResult {}

    record Breach(Severity severity, double longBurn, double shortBurn) implements BurnRateResult {}

    /** Not enough data to judge. Never treated as a breach. */
    record InsufficientData(String reason) implements BurnRateResult {}
}
