package com.sentinel.seed;

import com.sentinel.slo.domain.Severity;
import com.sentinel.slo.domain.SloType;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Thirty days of resolved incidents, inserted on first boot.
 *
 * <p>An empty product looks broken. A reviewer who opens the incident list and finds nothing cannot
 * tell "working, nothing has happened yet" from "not working", and will assume the second. History
 * makes the list look like a system that has been running, and gives the severity filters and the
 * date range something to act on. About fifty lines for a disproportionate amount of credibility.
 *
 * <p>Rows are written with {@link JdbcTemplate} rather than through {@code IncidentService}. Going
 * through the real path would walk the state machine, publish Kafka events for every fake incident
 * and trigger an RCA draft for each — thirty days of synthetic history would announce itself as
 * thirty days of live traffic. Backdating rows directly is the honest way to fabricate a past.
 */
@Component
@Order(100)
@ConditionalOnProperty(name = "sentinel.demo-seed", havingValue = "true", matchIfMissing = true)
public class DemoDataSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoDataSeeder.class);

    private static final int DAYS = 30;

    /**
     * Fixed, so every boot of a fresh database produces the same history.
     *
     * <p>Screenshots and the demo GIF stay reproducible, and "it looked different last time" never
     * becomes a thing anyone has to investigate.
     */
    private static final long SEED = 20_260_806L;

    /** Plausible outages, weighted so the list is not uniformly CRITICAL. */
    private static final List<Scenario> SCENARIOS = List.of(
            new Scenario("ledger-service", List.of("ledger-service", "fraud-service", "payment-service"), 3),
            new Scenario("payment-service", List.of("payment-service", "cart-service", "checkout-service"), 2),
            new Scenario("catalog-service", List.of("catalog-service", "search-service"), 2),
            new Scenario("cart-service", List.of("cart-service", "checkout-service"), 2),
            new Scenario("checkout-service", List.of("checkout-service", "api-gateway"), 2),
            new Scenario("fraud-service", List.of("fraud-service", "payment-service"), 1),
            new Scenario("search-service", List.of("search-service"), 1));

    private record Scenario(String origin, List<String> affected, int weight) {}

    private final JdbcTemplate jdbc;
    private final Clock clock;

    DemoDataSeeder(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        Integer existing = jdbc.queryForObject("SELECT count(*) FROM incident", Integer.class);
        if (existing != null && existing > 0) {
            log.debug("incident history already present ({} rows), not seeding", existing);
            return;
        }

        var random = new Random(SEED);
        List<Scenario> weighted = new ArrayList<>();
        SCENARIOS.forEach(s -> {
            for (int i = 0; i < s.weight(); i++) {
                weighted.add(s);
            }
        });

        Instant now = clock.instant();
        int created = 0;

        // Walk backwards a day at a time, skipping some so the history has quiet days in it. A
        // perfectly regular incident every day reads as generated, which defeats the purpose.
        for (int daysAgo = DAYS; daysAgo >= 1; daysAgo--) {
            int perDay =
                    switch (random.nextInt(10)) {
                        case 0, 1, 2, 3 -> 0;
                        case 4, 5, 6, 7 -> 1;
                        default -> 2;
                    };
            for (int i = 0; i < perDay; i++) {
                Scenario scenario = weighted.get(random.nextInt(weighted.size()));
                Instant openedAt = now.minus(Duration.ofDays(daysAgo))
                        .truncatedTo(ChronoUnit.HOURS)
                        .plus(Duration.ofMinutes(random.nextInt(24 * 60)));
                if (openedAt.isAfter(now)) {
                    continue;
                }
                insertIncident(scenario, openedAt, random);
                created++;
            }
        }

        log.info("seeded {} resolved incidents across the last {} days", created, DAYS);
    }

    private void insertIncident(Scenario scenario, Instant openedAt, Random random) {
        UUID id = UUID.randomUUID();
        Severity severity = severityFor(random);

        // Time to detect the last member breach, then time to resolve. Both drawn from ranges that
        // look like real incident handling rather than round numbers.
        Duration spread = Duration.ofSeconds(15L + random.nextInt(120));
        Duration ttr = Duration.ofMinutes(8L + random.nextInt(95));

        Instant lastBreachAt = openedAt.plus(spread);
        Instant acknowledgedAt = openedAt.plus(Duration.ofMinutes(1L + random.nextInt(6)));
        Instant mitigatedAt = openedAt.plus(ttr.dividedBy(2));
        Instant resolvedAt = openedAt.plus(ttr);
        Instant rcaAt = openedAt.plus(Duration.ofSeconds(20L + random.nextInt(40)));

        jdbc.update(
                """
                INSERT INTO incident (
                    id, correlation_key, state, severity, origin_service,
                    opened_at, acknowledged_at, mitigated_at, resolved_at,
                    last_breach_at, breach_count, version,
                    rca_draft, rca_model, rca_generated_at, rca_fallback
                ) VALUES (?, ?, 'RESOLVED', ?, ?, ?, ?, ?, ?, ?, ?, 0, ?, 'template', ?, TRUE)
                """,
                id,
                scenario.origin(),
                severity.name(),
                scenario.origin(),
                java.sql.Timestamp.from(openedAt),
                java.sql.Timestamp.from(acknowledgedAt),
                java.sql.Timestamp.from(mitigatedAt),
                java.sql.Timestamp.from(resolvedAt),
                java.sql.Timestamp.from(lastBreachAt),
                scenario.affected().size(),
                historicalRca(scenario, severity, openedAt),
                java.sql.Timestamp.from(rcaAt));

        for (String service : scenario.affected()) {
            jdbc.update("INSERT INTO incident_affected_service (incident_id, service_name) VALUES (?, ?)", id, service);
        }

        // One breach entry per affected service, staggered so the timeline reads as a cascade.
        long step =
                Math.max(1, spread.toSeconds() / Math.max(1, scenario.affected().size()));
        int index = 0;
        for (String service : scenario.affected()) {
            Instant detectedAt = openedAt.plusSeconds(step * index);
            jdbc.update(
                    """
                    INSERT INTO incident_event_log (
                        id, incident_id, kind, event_id, service_name, slo_type, severity,
                        long_burn_rate, short_burn_rate, message, occurred_at
                    ) VALUES (?, ?, 'BREACH', ?, ?, ?, ?, ?, ?, NULL, ?)
                    """,
                    UUID.randomUUID(),
                    id,
                    UUID.randomUUID(),
                    service,
                    index % 2 == 0 ? SloType.AVAILABILITY.name() : SloType.LATENCY.name(),
                    severity.name(),
                    burnFor(severity) + random.nextDouble() * 4.0,
                    burnFor(severity) + random.nextDouble() * 6.0,
                    java.sql.Timestamp.from(detectedAt));
            index++;
        }

        jdbc.update(
                """
                INSERT INTO incident_event_log (id, incident_id, kind, message, occurred_at)
                VALUES (?, ?, 'STATE_CHANGE', ?, ?)
                """,
                UUID.randomUUID(),
                id,
                "OPEN -> RESOLVED (auto-resolver)",
                java.sql.Timestamp.from(resolvedAt));
    }

    private static Severity severityFor(Random random) {
        int roll = random.nextInt(10);
        if (roll < 2) {
            return Severity.CRITICAL;
        }
        return roll < 6 ? Severity.HIGH : Severity.MEDIUM;
    }

    private static double burnFor(Severity severity) {
        return switch (severity) {
            case CRITICAL -> 14.4;
            case HIGH -> 6.0;
            case MEDIUM -> 1.0;
        };
    }

    /**
     * A template-style draft for the historical rows, marked as a fallback because that is what it
     * is. Seeding rows that claim a model wrote them would be a small lie told on the demo screen.
     */
    private static String historicalRca(Scenario scenario, Severity severity, Instant openedAt) {
        return """
                SUMMARY
                %s severity incident affecting %d services, originating at %s.

                LIKELY ORIGIN
                %s breached first; the remaining services follow its dependency edges.

                BLAST RADIUS
                %s

                WHAT TO CHECK NEXT
                1. Recent deploys and config changes on %s.
                2. Saturation and dependency health for %s.
                3. Whether the downstream breaches recover once the origin does.

                (Generated without a language model. This is the recorded timeline, not a causal analysis.)
                """
                .formatted(
                        severity,
                        scenario.affected().size(),
                        openedAt,
                        scenario.origin(),
                        String.join(", ", scenario.affected()),
                        scenario.origin(),
                        scenario.origin());
    }
}
