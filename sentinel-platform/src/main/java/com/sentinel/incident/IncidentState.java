package com.sentinel.incident;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * The incident lifecycle.
 *
 * <p>Transitions live in one table rather than scattered {@code if} blocks, so the legal set is
 * readable in one place and the exhaustive test in {@code IncidentStateTest} can enumerate it.
 */
public enum IncidentState {
    OPEN,
    ACKNOWLEDGED,
    MITIGATED,
    RESOLVED;

    private static final Map<IncidentState, Set<IncidentState>> ALLOWED;

    static {
        Map<IncidentState, Set<IncidentState>> allowed = new EnumMap<>(IncidentState.class);
        // RESOLVED is reachable from every non-terminal state: auto-resolution fires whenever the
        // breaches stop, whatever a human has or has not done to the incident in the meantime.
        //
        // Each set is wrapped as well as the map: an unmodifiable map still hands out its mutable
        // values, and allowedTargets() is on the public API and reachable from the error response.
        allowed.put(OPEN, Collections.unmodifiableSet(EnumSet.of(ACKNOWLEDGED, RESOLVED)));
        allowed.put(ACKNOWLEDGED, Collections.unmodifiableSet(EnumSet.of(MITIGATED, RESOLVED)));
        allowed.put(MITIGATED, Collections.unmodifiableSet(EnumSet.of(RESOLVED)));
        allowed.put(RESOLVED, Collections.unmodifiableSet(EnumSet.noneOf(IncidentState.class)));
        ALLOWED = Collections.unmodifiableMap(allowed);
    }

    public boolean canTransitionTo(IncidentState target) {
        return ALLOWED.get(this).contains(target);
    }

    public Set<IncidentState> allowedTargets() {
        return ALLOWED.get(this);
    }

    public boolean isTerminal() {
        return this == RESOLVED;
    }
}
