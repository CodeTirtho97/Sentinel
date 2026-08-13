package com.sentinel.slo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ShardAssignmentTest {

    private static final List<String> FLEET =
            List.of("checkout-service", "cart-service", "payment-service", "ledger-service");

    @Test
    void singleShardOwnsEverything() {
        var shard = new ShardAssignment(0, 1);

        assertThat(FLEET).allMatch(shard::owns);
    }

    @Test
    void shardsPartitionTheFleetWithoutOverlapOrGaps() {
        var shardA = new ShardAssignment(0, 2);
        var shardB = new ShardAssignment(1, 2);

        Set<String> ownedByA = new HashSet<>(FLEET.stream().filter(shardA::owns).toList());
        Set<String> ownedByB = new HashSet<>(FLEET.stream().filter(shardB::owns).toList());

        assertThat(ownedByA).doesNotContainAnyElementsOf(ownedByB);
        Set<String> union = new HashSet<>(ownedByA);
        union.addAll(ownedByB);
        assertThat(union).containsExactlyInAnyOrderElementsOf(FLEET);
    }

    @Test
    void assignmentIsStableAcrossInstances() {
        var first = new ShardAssignment(1, 4);
        var second = new ShardAssignment(1, 4);

        assertThat(FLEET.stream().filter(first::owns).toList())
                .isEqualTo(FLEET.stream().filter(second::owns).toList());
    }

    @Test
    void negativeHashCodesAreStillClaimedByExactlyOneShard() {
        // "polygenelubricants" is the classic negative-hashCode string; floorMod must absorb it.
        assertThat("polygenelubricants".hashCode()).isNegative();

        long claimants = java.util.stream.IntStream.range(0, 3)
                .filter(i -> new ShardAssignment(i, 3).owns("polygenelubricants"))
                .count();

        assertThat(claimants).isEqualTo(1);
    }

    @ParameterizedTest(name = "shardIndex={0} shardCount={1} is rejected")
    @CsvSource({"0, 0", "0, -1", "2, 2", "-1, 3", "5, 3"})
    void rejectsImpossibleShardConfiguration(int index, int count) {
        assertThatThrownBy(() -> new ShardAssignment(index, count)).isInstanceOf(IllegalArgumentException.class);
    }
}
