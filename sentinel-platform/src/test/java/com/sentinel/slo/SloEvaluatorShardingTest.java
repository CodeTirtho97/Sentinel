package com.sentinel.slo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.sentinel.config.SentinelProperties;
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
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Scenario 9: a two-shard deployment must evaluate every SLO exactly once between the two shards.
 *
 * <p>Built with stubs rather than containers — the property under test is arithmetic on the service
 * name, and nothing about it needs a broker or a database.
 */
class SloEvaluatorShardingTest {

    private static final List<String> SERVICES = List.of(
            "checkout-service",
            "cart-service",
            "payment-service",
            "ledger-service",
            "search-service",
            "auth-service",
            "notify-service",
            "billing-service");

    /** Every window returns a ratio far past the CRITICAL threshold, so every SLO breaches. */
    private static final double BREACHING_RATIO = 0.05;

    @Test
    @DisplayName("two shards evaluate disjoint service sets whose union is complete")
    void shardsPartitionTheFleet() {
        List<String> shardZero = servicesEvaluatedBy(0, 2);
        List<String> shardOne = servicesEvaluatedBy(1, 2);

        assertThat(shardZero).doesNotContainAnyElementsOf(shardOne);
        assertThat(concat(shardZero, shardOne)).containsExactlyInAnyOrderElementsOf(SERVICES);

        // Both shards must actually be carrying work, or "disjoint and complete" is satisfied
        // trivially by one shard owning everything.
        assertThat(shardZero).isNotEmpty();
        assertThat(shardOne).isNotEmpty();
    }

    @Test
    @DisplayName("the default 0/1 shard owns the whole fleet")
    void singleShardOwnsEverything() {
        assertThat(servicesEvaluatedBy(0, 1)).containsExactlyInAnyOrderElementsOf(SERVICES);
    }

    @Test
    @DisplayName("three shards still partition the fleet exactly once")
    void threeShardsPartitionTheFleet() {
        List<String> all = new ArrayList<>();
        for (int index = 0; index < 3; index++) {
            all.addAll(servicesEvaluatedBy(index, 3));
        }
        assertThat(all).containsExactlyInAnyOrderElementsOf(SERVICES);
    }

    private static List<String> concat(List<String> a, List<String> b) {
        List<String> all = new ArrayList<>(a);
        all.addAll(b);
        return all;
    }

    /** Runs one cycle for the given shard and returns the services it published a breach for. */
    private static List<String> servicesEvaluatedBy(int shardIndex, int shardCount) {
        var repository = mock(SloDefinitionRepository.class);
        when(repository.findByEnabledTrue())
                .thenReturn(SERVICES.stream()
                        .map(SloEvaluatorShardingTest::availabilitySlo)
                        .toList());

        // The real in-memory publisher rather than an ad-hoc lambda: it is one of the five seams,
        // and a seam nothing exercises is decoration.
        var publisher = new InMemoryEventPublisher();

        var props = new SentinelProperties();
        props.getEvaluation().setShardIndex(shardIndex);
        props.getEvaluation().setShardCount(shardCount);

        new SloEvaluator(
                        repository,
                        allServicesBreaching(),
                        calculator(),
                        new ShardAssignment(shardIndex, shardCount),
                        windows(),
                        new SimpleMeterRegistry(),
                        publisher,
                        recordingStore(),
                        Clock.fixed(Instant.parse("2026-08-06T02:14:31Z"), ZoneOffset.UTC),
                        props)
                .evaluateCycle();

        // Keyed by service name so one service's breaches stay ordered within a partition.
        assertThat(publisher.published()).allSatisfy(record -> {
            assertThat(record.topic()).isEqualTo(Topics.SLO_BREACH);
            assertThat(record.key()).isEqualTo(((SloBreachEvent) record.payload()).serviceName());
        });

        return publisher.payloads(Topics.SLO_BREACH, SloBreachEvent.class).stream()
                .map(SloBreachEvent::serviceName)
                .toList();
    }

    /** The evaluator seeds the correlation window as it publishes; nothing here reads it back. */
    private static CorrelationStore recordingStore() {
        return new CorrelationStore() {
            @Override
            public void record(SloBreachEvent event) {}

            @Override
            public List<SloBreachEvent> recentWithin(Duration window, Instant now) {
                return List.of();
            }

            @Override
            public boolean isHealthy() {
                return true;
            }
        };
    }

    private static SloDefinitionEntity availabilitySlo(String serviceName) {
        return new SloDefinitionEntity(
                UUID.nameUUIDFromBytes(serviceName.getBytes()),
                serviceName,
                SloType.AVAILABILITY,
                0.999,
                null,
                Duration.ofDays(30),
                true,
                Instant.parse("2026-08-06T00:00:00Z"));
    }

    private static MetricsSource allServicesBreaching() {
        var metrics = mock(MetricsSource.class);
        Map<String, ErrorRatio> ratios = new HashMap<>();
        for (String service : SERVICES) {
            ratios.put(service, new ErrorRatio(BREACHING_RATIO, 10_000, 1.0, Instant.parse("2026-08-06T02:14:31Z")));
        }
        when(metrics.errorRatios(any(), any(), any())).thenReturn(ratios);
        return metrics;
    }

    private static BurnRateCalculator calculator() {
        return new BurnRateCalculator(windowTable(), 60, 0.75);
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
