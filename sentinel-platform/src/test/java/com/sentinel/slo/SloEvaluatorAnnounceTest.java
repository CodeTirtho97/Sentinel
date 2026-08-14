package com.sentinel.slo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.sentinel.config.SentinelProperties;
import com.sentinel.correlation.BreachRef;
import com.sentinel.correlation.CorrelationStore;
import com.sentinel.events.InMemoryEventPublisher;
import com.sentinel.events.SloBreachEvent;
import com.sentinel.events.Topics;
import com.sentinel.slo.domain.Severity;
import com.sentinel.slo.domain.SloType;
import com.sentinel.slo.domain.Window;
import com.sentinel.slo.math.BurnRateCalculator;
import com.sentinel.slo.metrics.ErrorRatio;
import com.sentinel.slo.metrics.MetricsSource;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * An ongoing breach is announced when it starts, when its severity moves, and on a heartbeat —
 * not on every cycle.
 *
 * <p>Re-announcing every cycle is what buried the breach consumer under an 18,310 message backlog
 * in the load test: at 4,000 breaching SLOs it produced 16,000 events a minute, none of which told
 * any consumer something it did not already know.
 *
 * <p>The heartbeat is the other half and matters just as much. Auto-resolution reads
 * {@code lastBreachAt}, so a breach that goes completely silent between state changes would have its
 * incident closed underneath it while the service was still failing.
 */
class SloEvaluatorAnnounceTest {

    private static final String SERVICE = "ledger-service";
    private static final Instant START = Instant.parse("2026-08-06T02:00:00Z");

    /** Far past the CRITICAL threshold. */
    private static final double CRITICAL_RATIO = 0.05;

    /** Past MEDIUM, nowhere near CRITICAL — used to move severity without clearing the breach. */
    private static final double MEDIUM_RATIO = 0.0015;

    @Test
    @DisplayName("a newly breaching SLO is announced immediately")
    void firstBreachIsAnnounced() {
        var harness = new Harness();
        assertThat(harness.cycleAt(START, CRITICAL_RATIO)).containsExactly(SERVICE);
    }

    @Test
    @DisplayName("the same breach on the next cycle is suppressed, not republished")
    void ongoingBreachIsSuppressed() {
        var harness = new Harness();
        harness.cycleAt(START, CRITICAL_RATIO);

        assertThat(harness.cycleAt(START.plusSeconds(15), CRITICAL_RATIO)).isEmpty();
        assertThat(harness.cycleAt(START.plusSeconds(30), CRITICAL_RATIO)).isEmpty();
        assertThat(harness.suppressed()).isEqualTo(2);
    }

    @Test
    @DisplayName("the heartbeat re-announces once the republish interval has elapsed")
    void heartbeatReannounces() {
        var harness = new Harness();
        harness.cycleAt(START, CRITICAL_RATIO);

        // One second short of the two-minute interval: still nothing.
        assertThat(harness.cycleAt(START.plusSeconds(119), CRITICAL_RATIO)).isEmpty();

        // On the interval: announced again, so lastBreachAt keeps moving and the auto-resolver
        // never sees a live incident as quiet.
        assertThat(harness.cycleAt(START.plusSeconds(120), CRITICAL_RATIO)).containsExactly(SERVICE);
    }

    @Test
    @DisplayName("a severity change is announced immediately, without waiting for the heartbeat")
    void severityChangeIsAnnounced() {
        var harness = new Harness();
        assertThat(harness.cycleAt(START, MEDIUM_RATIO)).containsExactly(SERVICE);

        // Seconds later, still inside the heartbeat interval, but the incident just got worse.
        // Waiting two minutes to mention that would defeat the point of severity.
        List<String> escalated = harness.cycleAt(START.plusSeconds(15), CRITICAL_RATIO);
        assertThat(escalated).containsExactly(SERVICE);
        assertThat(harness.lastSeverity()).isEqualTo(Severity.CRITICAL);
    }

    @Test
    @DisplayName("recovering clears the memory, so a fresh breach announces at once")
    void recoveryResetsTheAnnouncement() {
        var harness = new Harness();
        harness.cycleAt(START, CRITICAL_RATIO);

        // Healthy: nothing breaches, and the SLO is forgotten.
        assertThat(harness.cycleAt(START.plusSeconds(15), 0.0)).isEmpty();

        // Breaking again immediately afterwards is news, even though the heartbeat is not due.
        assertThat(harness.cycleAt(START.plusSeconds(30), CRITICAL_RATIO)).containsExactly(SERVICE);
    }

    @Test
    @DisplayName("only announced breaches enter the correlation window")
    void suppressedBreachesAreNotRecorded() {
        var harness = new Harness();
        harness.cycleAt(START, CRITICAL_RATIO);
        harness.cycleAt(START.plusSeconds(15), CRITICAL_RATIO);
        harness.cycleAt(START.plusSeconds(30), CRITICAL_RATIO);

        // Recording a suppressed breach would keep the window growing with elapsed time again,
        // which is the cost this whole change exists to remove.
        assertThat(harness.recorded()).hasSize(1);
    }

    @Test
    @DisplayName("a republish interval at or beyond the correlation window fails startup")
    void republishMustBeShorterThanTheWindow() {
        var props = new SentinelProperties();
        props.getEvaluation().setBreachRepublishInterval(Duration.ofMinutes(5));
        props.getCorrelation().setWindow(Duration.ofMinutes(5));

        // Equal is already too long: a service would drop out of the window on the very cycle its
        // heartbeat was due, and the race decides whether correlation still sees it.
        assertThatThrownBy(() -> new Harness(props))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("breach-republish-interval");
    }

    @Test
    @DisplayName("a republish interval beyond auto-resolve-after fails startup")
    void republishMustBeShorterThanAutoResolve() {
        var props = new SentinelProperties();
        props.getEvaluation().setBreachRepublishInterval(Duration.ofMinutes(30));
        props.getCorrelation().setWindow(Duration.ofHours(1));
        props.getCorrelation().setAutoResolveAfter(Duration.ofMinutes(10));

        assertThatThrownBy(() -> new Harness(props)).isInstanceOf(IllegalStateException.class);
    }

    /** One SLO, one evaluator, a clock the test moves by hand. */
    private static final class Harness {

        private final InMemoryEventPublisher publisher = new InMemoryEventPublisher();
        private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
        private final List<SloBreachEvent> recorded = new ArrayList<>();
        private final MutableClock clock = new MutableClock(START);
        private final MetricsSource metrics = mock(MetricsSource.class);
        private final SloEvaluator evaluator;

        Harness() {
            this(new SentinelProperties());
        }

        Harness(SentinelProperties props) {
            var repository = mock(SloDefinitionRepository.class);
            when(repository.findByEnabledTrue()).thenReturn(List.of(slo()));

            evaluator = new SloEvaluator(
                    repository,
                    metrics,
                    new BurnRateCalculator(windowTable(), 60, 0.75),
                    new ShardAssignment(0, 1),
                    windows(),
                    registry,
                    publisher,
                    store(),
                    clock,
                    props);
        }

        /** Runs one cycle at the given instant and returns the services announced by it. */
        List<String> cycleAt(Instant at, double ratio) {
            clock.set(at);
            when(metrics.errorRatios(any(), any(), any()))
                    .thenReturn(Map.of(SERVICE, new ErrorRatio(ratio, 10_000, 1.0, at)));

            int before =
                    publisher.payloads(Topics.SLO_BREACH, SloBreachEvent.class).size();
            evaluator.evaluateCycle();

            return publisher.payloads(Topics.SLO_BREACH, SloBreachEvent.class).stream()
                    .skip(before)
                    .map(SloBreachEvent::serviceName)
                    .toList();
        }

        Severity lastSeverity() {
            var all = publisher.payloads(Topics.SLO_BREACH, SloBreachEvent.class);
            return all.get(all.size() - 1).severity();
        }

        double suppressed() {
            return registry.counter("sentinel.breaches.suppressed").count();
        }

        List<SloBreachEvent> recorded() {
            return recorded;
        }

        private CorrelationStore store() {
            return new CorrelationStore() {
                @Override
                public void record(SloBreachEvent event) {
                    recorded.add(event);
                }

                @Override
                public List<BreachRef> recentWithin(Duration window, Instant now) {
                    return List.of();
                }

                @Override
                public boolean isHealthy() {
                    return true;
                }
            };
        }
    }

    /** A Clock the test advances, so nothing here sleeps. */
    private static final class MutableClock extends Clock {

        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void set(Instant at) {
            this.now = at;
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }
    }

    private static SloDefinitionEntity slo() {
        return new SloDefinitionEntity(
                UUID.nameUUIDFromBytes(SERVICE.getBytes()),
                SERVICE,
                SloType.AVAILABILITY,
                0.999,
                null,
                Duration.ofDays(30),
                true,
                START);
    }

    private static List<Window> windowTable() {
        return List.of(
                new Window(Severity.CRITICAL, Duration.ofHours(1), Duration.ofMinutes(5), 14.4),
                new Window(Severity.HIGH, Duration.ofHours(6), Duration.ofMinutes(30), 6.0),
                new Window(Severity.MEDIUM, Duration.ofDays(3), Duration.ofHours(6), 1.0));
    }

    private static TreeSet<Duration> windows() {
        Set<Duration> all = new LinkedHashSet<>();
        for (Window window : windowTable()) {
            all.add(window.longWindow());
            all.add(window.shortWindow());
        }
        return new TreeSet<>(all);
    }
}
