package com.sentinel.support;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * A clock tests can move.
 *
 * <p>Advancing this is how the auto-resolve scenario reaches its ten-minute threshold without the
 * suite sleeping for ten minutes — and, more importantly, without the flakiness a real sleep buys.
 */
public class MutableClock extends Clock {

    public static final Instant START = Instant.parse("2026-08-06T02:00:00Z");

    private volatile Instant now = START;

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }

    @Override
    public Instant instant() {
        return now;
    }

    public void advance(Duration amount) {
        now = now.plus(amount);
    }

    public void reset() {
        now = START;
    }
}
