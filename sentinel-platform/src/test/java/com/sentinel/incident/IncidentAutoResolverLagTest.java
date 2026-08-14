package com.sentinel.incident;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.assertj.core.api.Assertions.assertThat;

import com.sentinel.config.SentinelProperties;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Auto-resolution must not draw conclusions from data it knows is stale.
 *
 * <p>The sweep infers "the problem stopped" from "no breach arrived recently". That is only sound
 * while the breach consumer is current. Under a large storm it is not: the evaluator publishes
 * faster than the consumer drains, {@code lastBreachAt} freezes at whatever was last processed, and
 * the sweep closes incidents for services that are still actively failing.
 *
 * <p>Measured before the guard existed: a backlog of 18,310 messages and 4,243 live incidents
 * auto-resolved, every one at exactly the 600-second threshold, while 2,000 services were still
 * breaching. A silent all-clear during the precise event the product exists for.
 */
class IncidentAutoResolverLagTest {

    private static final Instant NOW = Instant.parse("2026-08-06T02:30:00Z");
    private static final String LAG_METRIC = "kafka.consumer.fetch.manager.records.lag";

    @Test
    @DisplayName("a caught-up consumer lets the sweep resolve quiet incidents")
    void resolvesWhenConsumerIsCurrent() {
        var fixture = new Fixture();
        fixture.lag.set(0.0);

        fixture.resolver.resolveQuietIncidents();

        verify(fixture.incidents).transition(any(UUID.class), any(), anyString());
        assertThat(fixture.skipped()).isZero();
    }

    @Test
    @DisplayName("a backlogged consumer stops the sweep entirely")
    void skipsWhenConsumerIsBehind() {
        var fixture = new Fixture();
        fixture.lag.set(5_000.0);

        fixture.resolver.resolveQuietIncidents();

        // Nothing is resolved, and the skip is counted rather than silent — an operator has to be
        // able to see that auto-resolution is currently disabled.
        verify(fixture.incidents, never()).transition(any(UUID.class), any(), anyString());
        assertThat(fixture.skipped()).isEqualTo(1);
    }

    @Test
    @DisplayName("lag inside the tolerance is not treated as being behind")
    void toleratesSmallInFlightLag() {
        var fixture = new Fixture();
        // Non-zero lag is normal in flight; a strict zero would disable auto-resolution in any
        // system that is actually carrying traffic.
        fixture.lag.set(99.0);

        fixture.resolver.resolveQuietIncidents();

        verify(fixture.incidents, times(1)).transition(any(UUID.class), any(), anyString());
    }

    @Test
    @DisplayName("an absent lag metric means not behind, so tests and the in-memory publisher still resolve")
    void missingMetricDoesNotBlockResolution() {
        // No gauge registered at all: the in-memory event publisher registers no Kafka client
        // metrics, and a missing gauge must not read as infinitely behind.
        var fixture = new Fixture(false);

        fixture.resolver.resolveQuietIncidents();

        verify(fixture.incidents).transition(any(UUID.class), any(), anyString());
    }

    @Test
    @DisplayName("a losing race against a landing breach is not an error")
    void transitionFailureIsSwallowed() {
        var fixture = new Fixture();
        fixture.lag.set(0.0);
        when(fixture.incidents.transition(any(UUID.class), any(), anyString()))
                .thenThrow(new IllegalStateTransitionException(
                        UUID.randomUUID(), IncidentState.RESOLVED, IncidentState.RESOLVED));

        // A breach landing between the scan and the transition moves the incident out from under
        // the sweep. Losing that race is correct behaviour, not a reason to fail the whole pass.
        fixture.resolver.resolveQuietIncidents();
    }

    /** One stale incident, a registry the test drives, and a resolver wired to both. */
    private static final class Fixture {

        private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
        private final AtomicReference<Double> lag = new AtomicReference<>(0.0);
        private final IncidentService incidents = mock(IncidentService.class);
        private final IncidentAutoResolver resolver;

        Fixture() {
            this(true);
        }

        Fixture(boolean registerLagGauge) {
            var repository = mock(IncidentRepository.class);
            var stale = mock(Incident.class);
            when(stale.getId()).thenReturn(UUID.randomUUID());
            when(repository.findStaleUnresolved(any())).thenReturn(List.of(stale));

            if (registerLagGauge) {
                registry.gauge(LAG_METRIC, Tags.of("topic", "slo.breach.v1"), lag, AtomicReference::get);
            }

            resolver = new IncidentAutoResolver(
                    repository,
                    incidents,
                    Clock.fixed(NOW, ZoneOffset.UTC),
                    new SentinelProperties(),
                    registry);
        }

        double skipped() {
            var counter = registry.find("sentinel.incidents.autoresolve.skipped").counter();
            return counter == null ? 0 : counter.count();
        }
    }
}
