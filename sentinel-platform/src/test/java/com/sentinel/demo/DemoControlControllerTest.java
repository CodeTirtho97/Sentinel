package com.sentinel.demo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sentinel.config.SentinelProperties;
import com.sentinel.incident.IncidentRepository;
import com.sentinel.slo.SloDefinitionService;
import com.sentinel.slo.api.SloRequests;
import com.sentinel.slo.domain.SloType;
import com.sentinel.slo.metrics.ErrorRatio;
import com.sentinel.slo.metrics.MetricsSource;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/**
 * The demo endpoints decide two things a viewer cannot check for themselves: whether it is safe to
 * inject failure yet, and what a cleared slate actually means. Both are judgements about data, and
 * both are wrong in ways that look fine on screen — a readiness gate that counts an unknown service
 * as healthy releases the demo early and misattributes the origin; a clear that reports success
 * without its caveat produces a button that appears to do nothing when the evaluator reopens the
 * incident a cycle later.
 *
 * <p>Driven against the constructor rather than through MockMvc on purpose. {@code @Profile("demo")}
 * governs whether the bean exists at all, which is a wiring question; what is under test here is the
 * arithmetic inside it.
 */
class DemoControlControllerTest {

    private static final double ERROR_BUDGET = 1.0 - 0.999;

    @Test
    @DisplayName("a service with no data is clear, but reported as unknown rather than healthy")
    void missingSeriesIsClearAndLabelled() {
        var fixture = new Fixture();

        var readiness = fixture.controller.readiness();

        assertThat(readiness.ready()).isTrue();
        assertThat(readiness.clear()).isEqualTo(2);
        assertThat(readiness.services())
                .extracting(DemoControlController.ServiceReadiness::detail)
                .containsOnly("no data yet");
        // Absent data must not be dressed up as a measurement.
        assertThat(readiness.services())
                .extracting(DemoControlController.ServiceReadiness::errorRatio)
                .containsOnlyNulls();
    }

    @Test
    @DisplayName("NaN is treated as absent, not as a burn rate")
    void nanIsTreatedAsNoData() {
        var fixture = new Fixture();
        fixture.ratio("ledger-service", Double.NaN);

        var readiness = fixture.controller.readiness();

        // NaN propagates through any comparison as false, so a NaN reaching the burn-rate branch
        // would silently report the service as breaching.
        assertThat(readiness.ready()).isTrue();
        assertThat(fixture.serviceNamed(readiness, "ledger-service").detail()).isEqualTo("no data yet");
    }

    @Test
    @DisplayName("burn below 1.0 is clear; at or above it is not")
    void burnRateDecidesClearance() {
        var fixture = new Fixture();
        fixture.ratio("ledger-service", ERROR_BUDGET * 0.5); // burn 0.5
        fixture.ratio("cart-service", ERROR_BUDGET * 4.0); // burn 4.0

        var readiness = fixture.controller.readiness();

        assertThat(readiness.ready()).isFalse();
        assertThat(readiness.clear()).isEqualTo(1);
        assertThat(readiness.total()).isEqualTo(2);
        assertThat(fixture.serviceNamed(readiness, "ledger-service").clear()).isTrue();

        var breaching = fixture.serviceNamed(readiness, "cart-service");
        assertThat(breaching.clear()).isFalse();
        assertThat(breaching.burnRate()).isEqualTo(4.0);
        assertThat(breaching.detail()).isEqualTo("start-up errors still in window");
    }

    @Test
    @DisplayName("readiness is judged over the shortest configured long window")
    void shortestLongWindowWins() {
        var fixture = new Fixture();
        fixture.window("critical", Duration.ofMinutes(2));
        fixture.window("high", Duration.ofMinutes(5));
        fixture.window("medium", null); // an unset row must not win the min()

        var readiness = fixture.controller.readiness();

        assertThat(readiness.window()).isEqualTo("2m");
        verify(fixture.metrics).errorRatios(SloType.AVAILABILITY, null, Duration.ofMinutes(2));
    }

    @Test
    @DisplayName("with no windows configured readiness falls back to 1h rather than failing")
    void windowFallsBackToOneHour() {
        var fixture = new Fixture();

        var readiness = fixture.controller.readiness();

        assertThat(readiness.window()).isEqualTo("1h");
    }

    @Test
    @DisplayName("seed creates an availability and a latency SLO per service")
    void seedCreatesBothTypesPerService() {
        var fixture = new Fixture();

        var result = fixture.controller.seed();

        assertThat(result.ok()).isTrue();
        assertThat(result.message()).isEqualTo("4 SLOs created, 0 already present");
        assertThat(result.details()).isEmpty();

        var requests = org.mockito.ArgumentCaptor.forClass(SloRequests.Create.class);
        verify(fixture.slos, times(4)).create(requests.capture());
        // The latency threshold must be set for LATENCY and absent for AVAILABILITY: a threshold on
        // an availability target is rejected downstream, and a missing one on latency has no bucket.
        assertThat(requests.getAllValues())
                .filteredOn(r -> r.type() == SloType.LATENCY)
                .allSatisfy(r -> assertThat(r.latencyThresholdMs()).isEqualTo(500));
        assertThat(requests.getAllValues())
                .filteredOn(r -> r.type() == SloType.AVAILABILITY)
                .allSatisfy(r -> assertThat(r.latencyThresholdMs()).isNull());
    }

    @Test
    @DisplayName("re-seeding reports duplicates as already present, not as failure")
    void seedIsSafeToRerun() {
        var fixture = new Fixture();
        doThrow(new IllegalStateException("duplicate target"))
                .when(fixture.slos)
                .create(any());

        var result = fixture.controller.seed();

        // Re-running the seed is the normal case when a demo is restarted, so a duplicate is an
        // expected outcome rather than an error the page should show in red.
        assertThat(result.ok()).isTrue();
        assertThat(result.message()).isEqualTo("0 SLOs created, 4 already present");
        assertThat(result.details()).hasSize(4).allMatch(d -> d.endsWith("already present"));
    }

    @Test
    @DisplayName("breaking a service that is not in the fleet fails loudly")
    void chaosRejectsUnknownService() {
        var fixture = new Fixture();

        var result = fixture.controller.chaos(java.util.List.of("ledger-service", "not-a-service"));

        // Silently skipping it would leave the demo waiting for a cascade that was never started.
        assertThat(result.ok()).isFalse();
        assertThat(result.message()).isEqualTo("Some injections failed");
        assertThat(result.details()).contains("not-a-service: not in the fleet");
        assertThat(result.details()).anyMatch(d -> d.startsWith("ledger-service: 35% errors"));
    }

    @Test
    @DisplayName("an unreachable service is reported, not thrown")
    void chaosReportsTransportFailure() {
        var fixture = new Fixture();
        when(fixture.http.post()).thenThrow(new IllegalStateException("connection refused"));

        var result = fixture.controller.chaos(java.util.List.of("ledger-service"));

        assertThat(result.ok()).isFalse();
        assertThat(result.details()).containsExactly("ledger-service: connection refused");
    }

    @Test
    @DisplayName("reset clears every fleet member and survives one being down")
    void resetReportsPartialFailure() {
        var fixture = new Fixture();

        assertThat(fixture.controller.reset().ok()).isTrue();

        when(fixture.http.post()).thenThrow(new IllegalStateException("connection refused"));
        var failed = fixture.controller.reset();

        assertThat(failed.ok()).isFalse();
        assertThat(failed.message()).isEqualTo("Some resets failed");
        assertThat(failed.details()).hasSize(2);
    }

    @Test
    @DisplayName("clear deletes the incidents and says the slate will stay clean")
    void clearOnAQuietFleet() {
        var fixture = new Fixture();
        when(fixture.incidents.count()).thenReturn(3L);

        var result = fixture.controller.clear();

        verify(fixture.incidents).deleteAllInBatch();
        assertThat(result.ok()).isTrue();
        assertThat(result.message())
                .isEqualTo("Cleared 3 incident(s). Reliability targets are untouched."
                        + " Nothing is failing now, so the slate stays clean.");
    }

    @Test
    @DisplayName("clear warns that a new incident is coming while errors are still in the window")
    void clearWarnsWhileErrorsRemain() {
        var fixture = new Fixture();
        fixture.window("critical", Duration.ofMinutes(2));
        when(fixture.incidents.count()).thenReturn(1L);
        fixture.ratio("cart-service", ERROR_BUDGET * 9.0);

        var result = fixture.controller.clear();

        // Deleting rows does not delete the errors that produced them. Without this the button
        // looks broken when the evaluator quite correctly reopens the incident a cycle later.
        assertThat(result.message())
                .isEqualTo("Cleared 1 incident(s). Reliability targets are untouched."
                        + " Note: 1 of 2 services still have errors inside the 2m window,"
                        + " so the evaluator may open a new incident until those age out.");
    }

    @Test
    @DisplayName("a service answering something other than UP is reported down")
    void statusReportsNonUpService() {
        var fixture = new Fixture();

        var statuses = fixture.controller.status();

        assertThat(statuses)
                .hasSize(2)
                .extracting(DemoControlController.ServiceStatus::up)
                .containsOnly(false);
    }

    @Test
    @DisplayName("a hung service is reported down rather than failing the whole probe")
    void statusSurvivesAnUnreachableService() {
        var fixture = new Fixture();
        when(fixture.http.get()).thenThrow(new IllegalStateException("connection refused"));

        var statuses = fixture.controller.status();

        // Mid-demo a broken service is the point, so probing one must not take the page down.
        assertThat(statuses)
                .hasSize(2)
                .extracting(DemoControlController.ServiceStatus::up)
                .containsOnly(false);
        assertThat(statuses)
                .extracting(DemoControlController.ServiceStatus::detail)
                .containsOnly("IllegalStateException");
    }

    /** Two fleet members, no metrics and no reachable services unless a test says otherwise. */
    private static final class Fixture {

        final SloDefinitionService slos = mock(SloDefinitionService.class);
        final MetricsSource metrics = mock(MetricsSource.class);
        final IncidentRepository incidents = mock(IncidentRepository.class);
        final RestClient http = mock(RestClient.class, RETURNS_DEEP_STUBS);
        final SentinelProperties props = new SentinelProperties();
        final Map<String, ErrorRatio> ratios = new LinkedHashMap<>();
        final DemoControlController controller;

        Fixture() {
            props.getDemo()
                    .setFleet(new LinkedHashMap<>(Map.of(
                            "ledger-service", "http://ledger:8080",
                            "cart-service", "http://cart:8080")));

            when(metrics.errorRatios(any(), any(), any())).thenReturn(ratios);

            RestClient.Builder builder = mock(RestClient.Builder.class);
            when(builder.build()).thenReturn(http);

            controller = new DemoControlController(slos, props, builder, metrics, incidents);
        }

        void ratio(String service, double value) {
            ratios.put(service, new ErrorRatio(value, 1_000L, 1.0, Instant.EPOCH));
        }

        void window(String severity, Duration longWindow) {
            var spec = new SentinelProperties.WindowSpec();
            spec.setLongWindow(longWindow);
            props.getSlo().getWindows().put(severity, spec);
        }

        DemoControlController.ServiceReadiness serviceNamed(DemoControlController.Readiness r, String name) {
            return r.services().stream()
                    .filter(s -> s.name().equals(name))
                    .findFirst()
                    .orElseThrow();
        }
    }
}
