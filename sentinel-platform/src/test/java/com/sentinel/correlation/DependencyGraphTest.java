package com.sentinel.correlation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The component walk, without a database. */
class DependencyGraphTest {

    /** checkout -> cart, payment; cart -> payment; payment -> ledger. */
    private static final DependencyGraph FLEET = DependencyGraph.of(Map.of(
            "checkout-service", List.of("cart-service", "payment-service"),
            "cart-service", List.of("payment-service"),
            "payment-service", List.of("ledger-service"),
            "ledger-service", List.of()));

    @Test
    @DisplayName("a lone breach is its own component")
    void soloBreach() {
        assertThat(FLEET.componentOf("ledger-service", Set.of("ledger-service")))
                .containsExactly("ledger-service");
    }

    @Test
    @DisplayName("a full cascade collapses to one component — the headline behaviour")
    void fullCascade() {
        Set<String> breached = Set.of("ledger-service", "payment-service", "cart-service", "checkout-service");

        assertThat(FLEET.componentOf("checkout-service", breached))
                .containsExactlyInAnyOrder("checkout-service", "cart-service", "payment-service", "ledger-service");
    }

    @Test
    @DisplayName("the walk is undirected — a dependency's breach reaches its callers")
    void walksUpstream() {
        assertThat(FLEET.componentOf("ledger-service", Set.of("ledger-service", "payment-service")))
                .containsExactlyInAnyOrder("ledger-service", "payment-service");
    }

    @Test
    @DisplayName("the subgraph is induced by the breached set, so a healthy service does not bridge two breaches")
    void inducedSubgraphDoesNotBridgeThroughHealthyServices() {
        // checkout and ledger are connected in the full graph, but only through payment and cart,
        // neither of which is breaching. Walking the full graph here would merge two unrelated
        // incidents into one and make correlation look like it works when it does not.
        Set<String> breached = Set.of("checkout-service", "ledger-service");

        assertThat(FLEET.componentOf("checkout-service", breached)).containsExactly("checkout-service");
        assertThat(FLEET.componentOf("ledger-service", breached)).containsExactly("ledger-service");
    }

    @Test
    @DisplayName("an unknown service is a component of one rather than an error")
    void unknownService() {
        assertThat(FLEET.componentOf("mystery-service", Set.of("mystery-service", "ledger-service")))
                .containsExactly("mystery-service");
    }

    @Test
    @DisplayName("the triggering service is always in its own component")
    void triggeringServiceAlwaysIncluded() {
        assertThat(FLEET.componentOf("cart-service", Set.of())).containsExactly("cart-service");
    }

    @Test
    @DisplayName("depth counts distance down the call chain, taking the longest path")
    void depth() {
        assertThat(FLEET.depthOf("checkout-service")).isZero();
        assertThat(FLEET.depthOf("cart-service")).isEqualTo(1);
        // payment is reachable at depth 1 via checkout and depth 2 via cart; the longest wins, so
        // the tie-break prefers the service further from the entry point.
        assertThat(FLEET.depthOf("payment-service")).isEqualTo(2);
        assertThat(FLEET.depthOf("ledger-service")).isEqualTo(3);
    }

    @Test
    @DisplayName("a cyclic topology terminates instead of overflowing the stack")
    void cycleDoesNotHang() {
        DependencyGraph cyclic = DependencyGraph.of(Map.of(
                "a", List.of("b"),
                "b", List.of("c"),
                "c", List.of("a")));

        assertThat(cyclic.componentOf("a", Set.of("a", "b", "c"))).containsExactlyInAnyOrder("a", "b", "c");
        assertThat(cyclic.depthOf("a")).isGreaterThanOrEqualTo(0);
    }

    @Test
    void edgesExposeTheConfiguredTopology() {
        assertThat(FLEET.edges().get("checkout-service")).containsExactlyInAnyOrder("cart-service", "payment-service");
        assertThat(FLEET.edges().get("ledger-service")).isEmpty();
    }
}
