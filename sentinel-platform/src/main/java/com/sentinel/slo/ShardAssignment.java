package com.sentinel.slo;

/**
 * Decides which evaluator replica owns a service.
 *
 * <p>At the default 0 of 1 it owns everything, so single-instance deployments pay nothing for it.
 * Scaling out is then a config change rather than a rewrite.
 */
public class ShardAssignment {

    private final int shardIndex;
    private final int shardCount;

    public ShardAssignment(int shardIndex, int shardCount) {
        if (shardCount < 1) {
            throw new IllegalArgumentException("shardCount must be at least 1: " + shardCount);
        }
        if (shardIndex < 0 || shardIndex >= shardCount) {
            throw new IllegalArgumentException(
                    "shardIndex " + shardIndex + " out of range for shardCount " + shardCount);
        }
        this.shardIndex = shardIndex;
        this.shardCount = shardCount;
    }

    public boolean owns(String serviceName) {
        if (shardCount <= 1) {
            return true;
        }
        // String.hashCode is specified by the JDK, so the split is stable across JVMs and restarts.
        return Math.floorMod(serviceName.hashCode(), shardCount) == shardIndex;
    }

    public int shardIndex() {
        return shardIndex;
    }

    public int shardCount() {
        return shardCount;
    }
}
