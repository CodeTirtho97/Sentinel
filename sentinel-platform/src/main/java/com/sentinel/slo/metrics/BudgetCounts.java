package com.sentinel.slo.metrics;

import java.time.Instant;

public record BudgetCounts(long errors, long total, Instant asOf) {}
