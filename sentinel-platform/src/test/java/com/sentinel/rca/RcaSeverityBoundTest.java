package com.sentinel.rca;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sentinel.config.SentinelProperties;
import com.sentinel.correlation.DedupeStore;
import com.sentinel.events.IncidentEvent;
import com.sentinel.incident.IncidentNotFoundException;
import com.sentinel.slo.domain.Severity;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.support.Acknowledgment;

/**
 * The model is the only part of this system that costs money per event, so its fan-out is bounded.
 *
 * <p>One call per incident is fine for the demo's single cascade and ruinous for a storm: a measured
 * run opened 7,632 incidents and attempted 7,632 drafts. Against a free tier of roughly 30 requests
 * a minute that is exhausted in seconds, after which the circuit breaker opens and every incident
 * receives the deterministic fallback anyway — so the spend buys nothing.
 *
 * <p>Skipping is not the same as leaving the incident blank. {@code GET /incidents/{id}/rca} still
 * renders the deterministic timeline summary on demand, which is the identical artefact the
 * fallback path produces.
 */
class RcaSeverityBoundTest {

    @Test
    @DisplayName("a CRITICAL incident is drafted")
    void criticalIsDrafted() {
        var fixture = new Fixture(List.of(Severity.CRITICAL));

        fixture.deliver(Severity.CRITICAL);

        verify(fixture.rca).draftFor(any(UUID.class), anyBoolean());
        verify(fixture.ack).acknowledge();
        assertThat(fixture.skipped(Severity.CRITICAL)).isZero();
    }

    @Test
    @DisplayName("a HIGH incident is skipped when only CRITICAL is configured")
    void highIsSkippedByDefault() {
        var fixture = new Fixture(List.of(Severity.CRITICAL));

        fixture.deliver(Severity.HIGH);

        verify(fixture.rca, never()).draftFor(any(UUID.class), anyBoolean());
        assertThat(fixture.skipped(Severity.HIGH)).isEqualTo(1);

        // Still acked and still marked processed: a skipped incident is a decision, not a message
        // to redeliver forever.
        verify(fixture.ack).acknowledge();
        verify(fixture.dedupe).markProcessed(any(UUID.class));
    }

    @Test
    @DisplayName("widening the configured severities re-enables drafting")
    void configurationWidensTheBound() {
        var fixture = new Fixture(List.of(Severity.CRITICAL, Severity.HIGH));

        fixture.deliver(Severity.HIGH);

        verify(fixture.rca).draftFor(any(UUID.class), anyBoolean());
    }

    @Test
    @DisplayName("an already-processed incident is not drafted twice")
    void dedupeShortCircuits() {
        var fixture = new Fixture(List.of(Severity.CRITICAL));
        when(fixture.dedupe.alreadyProcessed(any(UUID.class))).thenReturn(true);

        fixture.deliver(Severity.CRITICAL);

        verify(fixture.rca, never()).draftFor(any(UUID.class), anyBoolean());
        verify(fixture.ack).acknowledge();
    }

    @Test
    @DisplayName("an incident deleted before drafting is counted, not dead-lettered")
    void orphanedIncidentIsCounted() {
        var fixture = new Fixture(List.of(Severity.CRITICAL));
        when(fixture.rca.draftFor(any(UUID.class), anyBoolean()))
                .thenThrow(new IncidentNotFoundException(UUID.randomUUID()));

        // Retrying cannot bring the row back and the DLT is for messages that need a human, so this
        // path acks and counts instead.
        fixture.deliver(Severity.CRITICAL);

        assertThat(fixture.registry.counter("sentinel.rca.orphaned").count()).isEqualTo(1);
        verify(fixture.ack).acknowledge();
    }

    private static final class Fixture {

        private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
        private final RcaService rca = mock(RcaService.class);
        private final DedupeStore dedupe = mock(DedupeStore.class);
        private final Acknowledgment ack = mock(Acknowledgment.class);
        private final RcaConsumer consumer;

        Fixture(List<Severity> draftSeverities) {
            var props = new SentinelProperties();
            props.getRca().setDraftSeverities(draftSeverities);
            consumer = new RcaConsumer(rca, dedupe, registry, props);
        }

        void deliver(Severity severity) {
            consumer.onIncidentOpened(
                    new IncidentEvent.Opened(
                            UUID.randomUUID(),
                            "ledger-service",
                            severity,
                            "ledger-service",
                            Set.of("ledger-service"),
                            Instant.parse("2026-08-06T02:14:31Z")),
                    ack);
        }

        double skipped(Severity severity) {
            var counter = registry.find("sentinel.rca.skipped")
                    .tag("severity", severity.name())
                    .counter();
            return counter == null ? 0 : counter.count();
        }
    }
}
