package com.sentinel.correlation;

import com.sentinel.events.SloBreachEvent;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Seam: the sliding window of recent breaches.
 *
 * <p>Redis rather than Postgres because this is a five-minute hot set read on every event and
 * expired by TTL — a workload a relational table is the wrong shape for. The documented scale fix is
 * a Kafka Streams windowed state store partitioned by correlation key, which is why this is an
 * interface rather than a class.
 */
public interface CorrelationStore {

    /** Adds a breach to the window. Idempotent: re-recording the same event does not duplicate it. */
    void record(SloBreachEvent event);

    /**
     * Every service that breached within {@code window} of {@code now}, oldest first, one entry per
     * service carrying its earliest breach still inside the window.
     *
     * <p>Deliberately not the events themselves. Correlation needs the service name to walk the
     * graph and the timestamp to infer the origin, and nothing else — see {@link BreachRef} for why
     * returning whole events made this the most expensive operation in the system.
     */
    List<BreachRef> recentWithin(Duration window, Instant now);

    /** Feeds the readiness probe in Phase 4. */
    boolean isHealthy();
}
