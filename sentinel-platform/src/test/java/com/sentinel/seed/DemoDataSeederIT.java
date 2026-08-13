package com.sentinel.seed;

import static org.assertj.core.api.Assertions.assertThat;

import com.sentinel.incident.Incident;
import com.sentinel.incident.IncidentState;
import com.sentinel.support.AbstractIntegrationTest;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.test.context.TestPropertySource;

/**
 * The seeded history has to be plausible, and it has to be inert.
 *
 * <p>Inert matters more than it sounds: every row is RESOLVED, so none of it collides with the
 * partial unique index that a live incident needs, and a demo run against a seeded database behaves
 * exactly like one against an empty one.
 */
@TestPropertySource(properties = "sentinel.demo-seed=true")
class DemoDataSeederIT extends AbstractIntegrationTest {

    @Autowired
    private DemoDataSeeder seeder;

    private static final ApplicationArguments NO_ARGS = new DefaultApplicationArguments();

    @Test
    @DisplayName("seeds a month of resolved incidents, and does not seed twice")
    void seedsOnceAndOnlyOnce() {
        // The base class truncates every table before each test, so the startup run is already
        // undone and this is a genuine first seed.
        seeder.run(NO_ARGS);

        List<Incident> seeded = incidents.findAll();
        assertThat(seeded).isNotEmpty();

        // Re-running must be a no-op. A restarting container that re-seeded every boot would grow
        // the history without bound.
        int countAfterFirst = seeded.size();
        seeder.run(NO_ARGS);
        assertThat(incidents.count()).isEqualTo(countAfterFirst);
    }

    @Test
    @DisplayName("every seeded incident is resolved, attributed, and internally consistent")
    void seededHistoryIsCoherent() {
        seeder.run(NO_ARGS);

        var now = clock.instant();
        for (Incident incident : incidents.findAll()) {
            assertThat(incident.getState()).isEqualTo(IncidentState.RESOLVED);
            assertThat(incident.getResolvedAt()).isNotNull().isAfter(incident.getOpenedAt());
            assertThat(incident.getAffectedServices()).isNotEmpty();
            assertThat(incident.getOriginService()).isNotBlank();

            // Inside the advertised window, and never in the future.
            assertThat(incident.getOpenedAt())
                    .isAfter(now.minus(Duration.ofDays(31)))
                    .isBefore(now);

            // A history claiming a model wrote its drafts would be a small lie on the demo screen.
            assertThat(incident.hasRca()).isTrue();
            assertThat(incident.getRcaFallback()).isTrue();

            assertThat(eventLog.findByIncidentIdOrderByOccurredAtAsc(incident.getId()))
                    .isNotEmpty();
        }
    }

    @Test
    @DisplayName("seeded history leaves the active-incident index free for a live incident")
    void seededHistoryDoesNotBlockLiveIncidents() {
        seeder.run(NO_ARGS);

        // Every seeded row is RESOLVED, so the partial unique index holds no key and a real
        // incident can open under an origin that appears repeatedly in the history.
        assertThat(incidents.lockActiveIdByCorrelationKey("ledger-service")).isEmpty();
    }
}
