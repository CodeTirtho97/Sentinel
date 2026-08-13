package com.sentinel.rca;

import static org.assertj.core.api.Assertions.assertThat;

import com.sentinel.slo.domain.Severity;
import com.sentinel.slo.domain.SloType;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The rendering half of {@link TimelineBuilder}, which is pure and needs no Spring context.
 *
 * <p>Worth testing directly because both outputs are contracts. The prompt is what the model is
 * held to "use only the data provided" against, and the plain summary is what a reviewer with no
 * API key actually reads — a fallback nobody has looked at is not a fallback.
 */
class TimelineBuilderTest {

    private static final Instant OPENED = Instant.parse("2026-08-06T02:14:31Z");

    private static IncidentContext cascade() {
        return new IncidentContext(
                UUID.fromString("00000000-0000-0000-0000-0000000000ff"),
                OPENED,
                Severity.CRITICAL,
                "ledger-service",
                Set.of("checkout-service", "cart-service", "payment-service", "ledger-service"),
                List.of(
                        new IncidentContext.Edge("checkout-service", "cart-service"),
                        new IncidentContext.Edge("cart-service", "payment-service"),
                        new IncidentContext.Edge("payment-service", "ledger-service")),
                List.of(
                        breach(OPENED, "ledger-service", SloType.AVAILABILITY, 22.1),
                        breach(OPENED.plusSeconds(15), "payment-service", SloType.LATENCY, 17.3),
                        breach(OPENED.plusSeconds(30), "cart-service", SloType.LATENCY, 9.2),
                        breach(OPENED.plusSeconds(30), "checkout-service", SloType.AVAILABILITY, 15.5)));
    }

    private static IncidentContext.BreachEntry breach(Instant at, String service, SloType type, double burn) {
        return new IncidentContext.BreachEntry(at, service, type, Severity.CRITICAL, burn, burn + 2);
    }

    @Test
    @DisplayName("the prompt carries the incident, the edges and the ordered timeline")
    void rendersStructuredContext() {
        String rendered = TimelineBuilder.render(cascade());

        assertThat(rendered)
                .contains("inferred_origin: ledger-service")
                .contains("severity: CRITICAL")
                .contains("payment-service -> ledger-service")
                .contains("02:14:31")
                .contains("burn=22.1");

        // Origin first, and the cascade in the order it happened — the single most important thing
        // the model is being shown.
        assertThat(rendered.indexOf("ledger-service    ")).isLessThan(rendered.indexOf("checkout-service  "));
    }

    @Test
    @DisplayName("the fallback summary produces the same four sections the model is asked for")
    void fallbackHasTheContractedSections() {
        String summary = TimelineBuilder.plainTextSummary(cascade());

        assertThat(summary)
                .contains("SUMMARY")
                .contains("LIKELY ORIGIN")
                .contains("BLAST RADIUS")
                .contains("WHAT TO CHECK NEXT");
        assertThat(summary).contains("ledger-service");
        // A reader must never be left guessing whether a model wrote this.
        assertThat(summary).contains("Generated without a language model");
    }

    @Test
    @DisplayName("an incident with no breaches renders without blowing up")
    void emptyTimelineIsSafe() {
        var empty = new IncidentContext(
                UUID.randomUUID(), OPENED, Severity.MEDIUM, null, Set.of("solo-service"), List.of(), List.of());

        assertThat(TimelineBuilder.render(empty))
                .contains("(no breaches recorded)")
                .contains("inferred_origin: unknown");
        // The fallback still has to produce something a human can act on, and must not claim an
        // origin it does not have.
        assertThat(TimelineBuilder.plainTextSummary(empty))
                .contains("could not be inferred")
                .contains("WHAT TO CHECK NEXT");
    }
}
