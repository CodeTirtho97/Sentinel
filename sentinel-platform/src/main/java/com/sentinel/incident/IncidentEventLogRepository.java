package com.sentinel.incident;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface IncidentEventLogRepository extends JpaRepository<IncidentEventLog, UUID> {

    List<IncidentEventLog> findByIncidentIdOrderByOccurredAtAsc(UUID incidentId);

    /** Backed by the partial unique index on event_id, so this is an index probe, not a scan. */
    boolean existsByEventId(UUID eventId);

    long countByIncidentId(UUID incidentId);
}
