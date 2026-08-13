package com.sentinel.incident;

import java.util.UUID;

/** Surfaces as HTTP 409 — the request was well formed, the incident was simply not in that state. */
public class IllegalStateTransitionException extends RuntimeException {

    private final transient IncidentState from;
    private final transient IncidentState to;

    public IllegalStateTransitionException(UUID incidentId, IncidentState from, IncidentState to) {
        super("incident " + incidentId + " cannot move from " + from + " to " + to + "; allowed targets are "
                + from.allowedTargets());
        this.from = from;
        this.to = to;
    }

    public IncidentState from() {
        return from;
    }

    public IncidentState to() {
        return to;
    }
}
