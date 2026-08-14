package com.sentinel.correlation;

import com.sentinel.events.SloBreachEvent;
import java.time.Instant;

/**
 * The only two fields correlation actually reads out of the recent-breach window.
 *
 * <p>The window used to hold fully serialized {@code SloBreachEvent}s, and every incoming event
 * deserialized all of them — then used the service name and the timestamp and discarded the rest.
 * At 4,000 breaching SLOs that was roughly 320 million JSON deserializations per evaluation cycle,
 * quadratic in the size of the storm, and it was what buried the breach consumer under an 18,310
 * message backlog.
 *
 * <p>Storing the pair directly makes the window members about six times smaller and removes Jackson
 * from the hot path entirely. It also collapses the two SLOs a service carries into one member,
 * since availability and latency breaching together are one breaching service as far as the
 * component walk is concerned — which halves the window again.
 *
 * <p>Narrowing the seam this way also shrinks what a Kafka Streams implementation would have to
 * reproduce: a windowed set of names and timestamps, rather than a windowed log of events.
 */
public record BreachRef(String serviceName, Instant detectedAt) {

    /** The projection an event collapses to once it is in the window. */
    public static BreachRef of(SloBreachEvent event) {
        return new BreachRef(event.serviceName(), event.detectedAt());
    }
}
