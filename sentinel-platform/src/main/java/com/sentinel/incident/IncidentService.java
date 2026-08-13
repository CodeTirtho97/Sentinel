package com.sentinel.incident;

import com.sentinel.correlation.CorrelationResult;
import com.sentinel.events.EventPublisher;
import com.sentinel.events.IncidentEvent;
import com.sentinel.events.SloBreachEvent;
import com.sentinel.events.Topics;
import com.sentinel.slo.domain.Severity;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class IncidentService {

    private static final Logger log = LoggerFactory.getLogger(IncidentService.class);

    private final IncidentRepository repository;
    private final IncidentEventLogRepository eventLog;
    private final EventPublisher publisher;
    private final MeterRegistry registry;
    private final Clock clock;

    IncidentService(
            IncidentRepository repository,
            IncidentEventLogRepository eventLog,
            EventPublisher publisher,
            MeterRegistry registry,
            Clock clock) {
        this.repository = repository;
        this.eventLog = eventLog;
        this.publisher = publisher;
        this.registry = registry;
        this.clock = clock;
    }

    /**
     * @param created true when this call opened the incident rather than widening an existing one
     * @param duplicate true when the breach had already been recorded and nothing changed
     */
    public record AttachOutcome(UUID incidentId, boolean created, boolean duplicate) {}

    /**
     * Attaches a breach to its incident, creating one if the correlation key has no active incident.
     *
     * <p>Idempotent on two axes. Concurrent breaches for one key race on the partial unique index and
     * exactly one insert wins; a redelivered breach is caught by the unique index on
     * {@code event_id} and widens nothing twice.
     */
    @Transactional
    public AttachOutcome openOrAttach(CorrelationResult correlation, SloBreachEvent event) {
        Instant now = clock.instant();
        String key = correlation.correlationKey();

        int inserted = repository.insertIfAbsent(
                UUID.randomUUID(), key, event.severity().name(), correlation.originService(), now, event.detectedAt());
        boolean created = inserted == 1;

        // Locks the row for the rest of this transaction, so two consumers widening the same
        // incident serialise here rather than clobbering each other's affected-services set.
        UUID incidentId = repository
                .lockActiveIdByCorrelationKey(key)
                .orElseThrow(() -> new IllegalStateException(
                        "no active incident for key " + key + " immediately after insert; retry via redelivery"));

        Incident incident =
                repository.findById(incidentId).orElseThrow(() -> new IncidentNotFoundException(incidentId));

        // The database-side half of dedupe. Redis skips the common case before we ever get here;
        // this closes the window between a commit and its Redis mark.
        if (eventLog.existsByEventId(event.eventId())) {
            log.debug("breach {} already recorded on incident {}", event.eventId(), incidentId);
            return new AttachOutcome(incidentId, false, true);
        }

        eventLog.save(IncidentEventLog.breach(
                incidentId,
                event.eventId(),
                event.serviceName(),
                event.sloType(),
                event.severity(),
                event.longBurnRate(),
                event.shortBurnRate(),
                event.detectedAt()));

        incident.recordBreach(event.severity(), correlation.component(), event.detectedAt());
        // Recomputed for display as the cascade fills in. The correlation key does not follow it —
        // re-keying a live incident is what splits one incident into one per breach.
        incident.setOriginService(correlation.originService());
        repository.save(incident);

        if (created) {
            registry.counter(
                            "sentinel.incidents.opened",
                            "severity",
                            incident.getSeverity().name())
                    .increment();
            log.info(
                    "OPENED incident {} key={} severity={} origin={}",
                    incidentId,
                    key,
                    incident.getSeverity(),
                    correlation.originService());
            publishAfterCommit(
                    Topics.INCIDENT_OPENED,
                    incidentId.toString(),
                    new IncidentEvent.Opened(
                            incidentId,
                            key,
                            incident.getSeverity(),
                            correlation.originService(),
                            Set.copyOf(correlation.component()),
                            incident.getOpenedAt()));
        } else {
            log.info(
                    "ATTACHED {} to incident {} (affected now {})",
                    event.serviceName(),
                    incidentId,
                    incident.getAffectedServices().size());
        }

        return new AttachOutcome(incidentId, created, false);
    }

    @Transactional
    public Incident transition(UUID id, IncidentState target, String actor) {
        Incident incident = repository.findById(id).orElseThrow(() -> new IncidentNotFoundException(id));
        IncidentState from = incident.getState();
        Instant now = clock.instant();

        incident.transitionTo(target, now);
        eventLog.save(IncidentEventLog.stateChange(id, from + " -> " + target + " (" + actor + ")", now));
        repository.save(incident);

        log.info("incident {} {} -> {} by {}", id, from, target, actor);
        publishAfterCommit(
                Topics.INCIDENT_STATE_CHANGED,
                id.toString(),
                new IncidentEvent.StateChanged(
                        id, incident.getCorrelationKey(), from.name(), target.name(), actor, now));
        return incident;
    }

    @Transactional(readOnly = true)
    public Incident get(UUID id) {
        return repository.findById(id).orElseThrow(() -> new IncidentNotFoundException(id));
    }

    @Transactional(readOnly = true)
    public List<IncidentEventLog> timeline(UUID id) {
        return eventLog.findByIncidentIdOrderByOccurredAtAsc(id);
    }

    @Transactional(readOnly = true)
    public Page<Incident> search(IncidentState state, Severity severity, Instant since, Pageable pageable) {
        return repository.search(state, severity, since, pageable);
    }

    /** Publishes only if the transaction actually commits — a rolled back incident must not be announced. */
    private void publishAfterCommit(String topic, String key, Object payload) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            publisher.publish(topic, key, payload);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                publisher.publish(topic, key, payload);
            }
        });
    }
}
