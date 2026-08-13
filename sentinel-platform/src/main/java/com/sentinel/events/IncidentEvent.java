package com.sentinel.events;

import com.sentinel.slo.domain.Severity;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/** Lifecycle events published by IncidentService. Keyed by incidentId. */
public sealed interface IncidentEvent {

    UUID incidentId();

    /** Published exactly once per incident, on creation. Triggers RCA drafting in Phase 3. */
    record Opened(
            UUID incidentId,
            String correlationKey,
            Severity severity,
            String originService,
            Set<String> affectedServices,
            Instant openedAt)
            implements IncidentEvent {}

    /** Published on every accepted state transition, including auto-resolution. */
    record StateChanged(UUID incidentId, String correlationKey, String from, String to, String actor, Instant changedAt)
            implements IncidentEvent {}
}
