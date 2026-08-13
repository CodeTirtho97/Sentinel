package com.sentinel.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sentinel.slo.domain.Severity;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SloBreachEventTest {

    private static final UUID SLO = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final Duration INTERVAL = Duration.ofSeconds(15);
    private static final Instant T = Instant.parse("2026-08-06T02:14:31Z");

    @Test
    @DisplayName("two evaluations inside one interval produce the same event id")
    void sameBucketSameId() {
        UUID first = SloBreachEvent.deterministicId(SLO, Severity.CRITICAL, T, INTERVAL);
        UUID second = SloBreachEvent.deterministicId(SLO, Severity.CRITICAL, T.plusSeconds(3), INTERVAL);

        assertThat(second).isEqualTo(first);
    }

    @Test
    @DisplayName("consecutive intervals produce different ids, so an ongoing breach keeps a timeline")
    void differentBucketDifferentId() {
        UUID first = SloBreachEvent.deterministicId(SLO, Severity.CRITICAL, T, INTERVAL);
        UUID next = SloBreachEvent.deterministicId(SLO, Severity.CRITICAL, T.plusSeconds(15), INTERVAL);

        assertThat(next).isNotEqualTo(first);
    }

    @Test
    @DisplayName("severity is part of the identity — an escalation is a new event")
    void severityChangesId() {
        UUID high = SloBreachEvent.deterministicId(SLO, Severity.HIGH, T, INTERVAL);
        UUID critical = SloBreachEvent.deterministicId(SLO, Severity.CRITICAL, T, INTERVAL);

        assertThat(critical).isNotEqualTo(high);
    }

    @Test
    @DisplayName("different SLOs never collide")
    void sloChangesId() {
        UUID other = UUID.fromString("22222222-2222-2222-2222-222222222222");

        assertThat(SloBreachEvent.deterministicId(other, Severity.CRITICAL, T, INTERVAL))
                .isNotEqualTo(SloBreachEvent.deterministicId(SLO, Severity.CRITICAL, T, INTERVAL));
    }

    @Test
    @DisplayName("bucketing floors, so it does not jump across the epoch or on negative instants")
    void bucketingFloorsConsistently() {
        Instant beforeEpoch = Instant.ofEpochMilli(-7_000);
        UUID a = SloBreachEvent.deterministicId(SLO, Severity.MEDIUM, beforeEpoch, INTERVAL);
        UUID b = SloBreachEvent.deterministicId(SLO, Severity.MEDIUM, Instant.ofEpochMilli(-1), INTERVAL);

        // Both fall in bucket -1 under floor division; truncation toward zero would split them.
        assertThat(b).isEqualTo(a);
    }

    @Test
    @DisplayName("it is a v3 (MD5) UUID — the JDK has no v5 factory, and the version nibble is checkable")
    void isVersionThree() {
        assertThat(SloBreachEvent.deterministicId(SLO, Severity.CRITICAL, T, INTERVAL)
                        .version())
                .isEqualTo(3);
    }

    @Test
    void factoryCarriesTheDerivedId() {
        SloBreachEvent event = SloBreachEvent.of(
                SLO,
                "ledger-service",
                com.sentinel.slo.domain.SloType.AVAILABILITY,
                Severity.CRITICAL,
                22.1,
                18.4,
                T,
                INTERVAL);

        assertThat(event.eventId()).isEqualTo(SloBreachEvent.deterministicId(SLO, Severity.CRITICAL, T, INTERVAL));
        assertThat(event.serviceName()).isEqualTo("ledger-service");
    }

    @Test
    void rejectsANonPositiveInterval() {
        assertThatThrownBy(() -> SloBreachEvent.deterministicId(SLO, Severity.CRITICAL, T, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
