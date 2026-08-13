package com.sentinel.correlation;

import com.sentinel.config.SentinelProperties;
import java.time.Duration;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Remembers which event ids have already been processed.
 *
 * <p><b>The mark happens after the database transaction commits, never before.</b> The tempting
 * version is a {@code SETNX} guard up front, and it silently loses events: mark, commit fails, Kafka
 * redelivers, the mark is found, the consumer logs DEBUG and returns — the breach is gone, with no
 * incident, no dead letter and no counter to notice it by. A dropped breach is the worst failure
 * this system has, because it makes the product quietly not do its job.
 *
 * <p>Marking afterwards leaves one race instead: a duplicate delivered between the commit and the
 * mark. That is precisely what the partial unique index on {@code incident} absorbs.
 *
 * <p>Deliberately a plain component rather than a sixth abstraction seam — there is no plausible
 * second implementation worth designing for.
 */
@Component
public class DedupeStore {

    private static final Logger log = LoggerFactory.getLogger(DedupeStore.class);

    private static final String PREFIX = "processed:";

    private final StringRedisTemplate redis;
    private final Duration ttl;

    DedupeStore(StringRedisTemplate redis, SentinelProperties props) {
        this.redis = redis;
        this.ttl = props.getCorrelation().getDedupeTtl();
    }

    public boolean alreadyProcessed(UUID eventId) {
        try {
            return Boolean.TRUE.equals(redis.hasKey(PREFIX + eventId));
        } catch (RuntimeException e) {
            // Redis being down must not stop breaches being processed. Failing open costs a
            // duplicate delivery, which the unique index turns into a no-op.
            log.warn("dedupe lookup failed for {}, processing anyway: {}", eventId, e.toString());
            return false;
        }
    }

    /** Call only after the transaction has committed. */
    public void markProcessed(UUID eventId) {
        try {
            redis.opsForValue().set(PREFIX + eventId, "1", ttl);
        } catch (RuntimeException e) {
            log.warn("could not mark {} processed: {}", eventId, e.toString());
        }
    }
}
