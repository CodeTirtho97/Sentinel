package com.sentinel.slo.domain;

/** Breach severity. Rank is explicit so reordering the constants cannot change comparisons. */
public enum Severity {
    MEDIUM(1),
    HIGH(2),
    CRITICAL(3);

    private final int rank;

    Severity(int rank) {
        this.rank = rank;
    }

    public int rank() {
        return rank;
    }

    public boolean isAtLeast(Severity other) {
        return this.rank >= other.rank;
    }

    /** Returns the more urgent of the two. Null-tolerant so it folds over an empty set. */
    public static Severity max(Severity a, Severity b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        return a.rank >= b.rank ? a : b;
    }
}
