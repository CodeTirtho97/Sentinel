package com.sentinel.loadtest;

import com.sentinel.events.EventPublisher;
import com.sentinel.events.SloBreachEvent;
import com.sentinel.events.Topics;
import com.sentinel.slo.domain.Severity;
import com.sentinel.slo.domain.SloType;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Replays duplicate breach events, for measurement 5.
 *
 * <p><b>{@code @Profile("loadtest")} is the safety story</b>, exactly as with the demo controller:
 * outside that profile the bean does not exist and the route is a 404, rather than being disabled
 * by a flag someone can flip.
 *
 * <p>This exists because the claim being tested — "10,000 duplicate deliveries produce zero
 * duplicate incidents" — is only worth anything if the duplicates travel the real path: the real
 * producer, the real topic, the real deterministic event id, the real consumer. Generating them
 * from outside with a console producer would mean hand-rolling the JSON and the {@code __TypeId__}
 * header, and would prove the test harness works rather than that the system does.
 */
@RestController
@RequestMapping("/api/v1/loadtest")
@Profile("loadtest")
public class LoadTestController {

    private static final Logger log = LoggerFactory.getLogger(LoadTestController.class);

    private final EventPublisher publisher;
    private final Clock clock;

    LoadTestController(EventPublisher publisher, Clock clock) {
        this.publisher = publisher;
        this.clock = clock;
    }

    /**
     * Publishes the same breach {@code count} times.
     *
     * <p>Every copy is byte-identical, and crucially shares one {@code eventId}: the id is derived
     * from (sloId, severity, evaluation bucket), and holding {@code detectedAt} fixed holds the
     * bucket fixed. Correct behaviour is one incident and one timeline entry, no matter the count.
     */
    @PostMapping("/replay")
    public Map<String, Object> replay(
            @RequestParam(defaultValue = "10000") int count,
            @RequestParam(defaultValue = "synth-c000-s4") String service) {

        // Fixed, not now(): a timestamp that drifts across the loop would cross an evaluation
        // bucket boundary, change the derived id, and produce genuinely distinct events — which
        // would make a passing result meaningless.
        var detectedAt = clock.instant();
        SloBreachEvent event = SloBreachEvent.of(
                UUID.nameUUIDFromBytes(("loadtest:" + service).getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                service,
                SloType.AVAILABILITY,
                Severity.CRITICAL,
                22.1,
                18.4,
                detectedAt,
                Duration.ofDays(365));

        long started = System.nanoTime();
        for (int i = 0; i < count; i++) {
            publisher.publish(Topics.SLO_BREACH, event.serviceName(), event);
        }
        long millis = (System.nanoTime() - started) / 1_000_000;

        log.info("replayed {} copies of event {} in {}ms", count, event.eventId(), millis);
        return Map.of(
                "published", count,
                "eventId", event.eventId().toString(),
                "service", service,
                "publishMillis", millis);
    }
}
