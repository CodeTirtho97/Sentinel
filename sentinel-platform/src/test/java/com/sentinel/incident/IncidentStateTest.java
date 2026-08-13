package com.sentinel.incident;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

class IncidentStateTest {

    /**
     * All sixteen (from, to) pairs in one table.
     *
     * <p>Enumerating the illegal pairs explicitly is the point: a transition map that accidentally
     * grows an entry is invisible to a test that only checks the legal ones.
     */
    @ParameterizedTest(name = "{0} -> {1} legal={2}")
    @CsvSource({
        "OPEN,         OPEN,         false",
        "OPEN,         ACKNOWLEDGED, true",
        "OPEN,         MITIGATED,    false",
        "OPEN,         RESOLVED,     true",
        "ACKNOWLEDGED, OPEN,         false",
        "ACKNOWLEDGED, ACKNOWLEDGED, false",
        "ACKNOWLEDGED, MITIGATED,    true",
        "ACKNOWLEDGED, RESOLVED,     true",
        "MITIGATED,    OPEN,         false",
        "MITIGATED,    ACKNOWLEDGED, false",
        "MITIGATED,    MITIGATED,    false",
        "MITIGATED,    RESOLVED,     true",
        "RESOLVED,     OPEN,         false",
        "RESOLVED,     ACKNOWLEDGED, false",
        "RESOLVED,     MITIGATED,    false",
        "RESOLVED,     RESOLVED,     false",
    })
    void transitionTable(IncidentState from, IncidentState to, boolean legal) {
        assertThat(from.canTransitionTo(to)).isEqualTo(legal);
        assertThat(from.allowedTargets().contains(to)).isEqualTo(legal);
    }

    @Test
    @DisplayName("RESOLVED is the only terminal state")
    void resolvedIsTerminal() {
        assertThat(IncidentState.RESOLVED.isTerminal()).isTrue();
        assertThat(IncidentState.RESOLVED.allowedTargets()).isEmpty();

        for (IncidentState state : IncidentState.values()) {
            if (state != IncidentState.RESOLVED) {
                assertThat(state.isTerminal()).isFalse();
            }
        }
    }

    @Test
    @DisplayName("every non-terminal state can be auto-resolved")
    void everyLiveStateReachesResolved() {
        for (IncidentState state : IncidentState.values()) {
            if (!state.isTerminal()) {
                assertThat(state.canTransitionTo(IncidentState.RESOLVED))
                        .as("%s must be auto-resolvable when breaches stop", state)
                        .isTrue();
            }
        }
    }

    @ParameterizedTest
    @EnumSource(IncidentState.class)
    @DisplayName("no state transitions to itself")
    void noSelfTransitions(IncidentState state) {
        assertThat(state.canTransitionTo(state)).isFalse();
    }

    @Test
    @DisplayName("the allowed-target sets are immutable")
    void allowedTargetsAreImmutable() {
        Set<IncidentState> targets = IncidentState.OPEN.allowedTargets();
        assertThat(targets).containsExactlyInAnyOrder(IncidentState.ACKNOWLEDGED, IncidentState.RESOLVED);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> targets.add(IncidentState.MITIGATED))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
