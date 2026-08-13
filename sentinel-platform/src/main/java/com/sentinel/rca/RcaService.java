package com.sentinel.rca;

import com.sentinel.incident.Incident;
import com.sentinel.incident.IncidentEventLog;
import com.sentinel.incident.IncidentEventLogRepository;
import com.sentinel.incident.IncidentNotFoundException;
import com.sentinel.incident.IncidentRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Drafts an incident's RCA and stores it.
 *
 * <p>Read, draft, write are three separate steps with the model call in the middle. Reading the
 * context and writing the draft take milliseconds; the model call can take ten seconds, and holding
 * a pooled database connection across it would let one slow provider drain the connection pool
 * during exactly the incident storm that produced the drafts.
 *
 * <p>The transaction boundaries are {@link TransactionTemplate} rather than {@code @Transactional}
 * deliberately. Annotating private steps and calling them from a public method on the same bean
 * bypasses the proxy entirely — the annotations would read correctly and do nothing. Programmatic
 * boundaries cannot be wrong that way, and here the boundaries are the point.
 */
@Service
public class RcaService {

    private static final Logger log = LoggerFactory.getLogger(RcaService.class);

    private final IncidentRepository incidents;
    private final IncidentEventLogRepository eventLog;
    private final TimelineBuilder timelines;
    private final RcaDrafter drafter;
    private final MeterRegistry registry;
    private final Clock clock;
    private final TransactionTemplate readTx;
    private final TransactionTemplate writeTx;

    RcaService(
            IncidentRepository incidents,
            IncidentEventLogRepository eventLog,
            TimelineBuilder timelines,
            RcaDrafter drafter,
            MeterRegistry registry,
            Clock clock,
            PlatformTransactionManager txManager) {
        this.incidents = incidents;
        this.eventLog = eventLog;
        this.timelines = timelines;
        this.drafter = drafter;
        this.registry = registry;
        this.clock = clock;
        this.readTx = new TransactionTemplate(txManager);
        this.readTx.setReadOnly(true);
        this.writeTx = new TransactionTemplate(txManager);
    }

    /**
     * Drafts for an incident unless it already has one.
     *
     * @param force redraft even when a draft exists — what {@code rca:regenerate} asks for
     * @return true if a draft was written
     */
    public boolean draftFor(UUID incidentId, boolean force) {
        IncidentContext context = readTx.execute(status -> loadContext(incidentId, force));
        if (context == null) {
            log.debug("incident {} already has an RCA draft, skipping", incidentId);
            return false;
        }

        // No transaction held here. This is the ten-second step.
        Timer.Sample sample = Timer.start(registry);
        RcaDraft draft = drafter.draft(context);
        sample.stop(registry.timer("sentinel.rca.duration", "fallback", String.valueOf(draft.fallback())));

        if (draft.fallback()) {
            registry.counter("sentinel.rca.fallbacks").increment();
        }

        boolean written = writeTx.execute(status -> store(incidentId, draft, force));
        if (Boolean.FALSE.equals(written)) {
            // Another drafter got there first. Not an error, and not worth a timeline entry.
            log.debug("incident {} was drafted concurrently; keeping the existing draft", incidentId);
            return false;
        }

        log.info("RCA drafted for incident {} by {} (fallback={})", incidentId, draft.model(), draft.fallback());
        return true;
    }

    /** Returns null when the incident already has a draft and no redraft was asked for. */
    private IncidentContext loadContext(UUID incidentId, boolean force) {
        Incident incident = incidents.findById(incidentId).orElseThrow(() -> new IncidentNotFoundException(incidentId));
        if (incident.hasRca() && !force) {
            return null;
        }
        List<IncidentEventLog> timeline = eventLog.findByIncidentIdOrderByOccurredAtAsc(incidentId);
        return timelines.build(incident, timeline);
    }

    /**
     * Writes the draft, if this call is the one that gets to.
     *
     * <p>The timeline entry is written only when the update actually landed, so a losing racer does
     * not leave a second "RCA drafted" line on an incident that has one draft.
     *
     * @return true if this call wrote the draft
     */
    private boolean store(UUID incidentId, RcaDraft draft, boolean force) {
        Instant now = clock.instant();
        int updated = incidents.updateRca(incidentId, draft.text(), draft.model(), draft.fallback(), now, force);
        if (updated == 0) {
            return false;
        }

        eventLog.save(IncidentEventLog.rca(
                incidentId, "RCA drafted by " + draft.model() + (draft.fallback() ? " (fallback)" : ""), now));
        return true;
    }
}
