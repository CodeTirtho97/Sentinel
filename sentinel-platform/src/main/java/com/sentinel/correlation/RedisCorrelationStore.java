package com.sentinel.correlation;

import com.sentinel.config.SentinelProperties;
import com.sentinel.events.SloBreachEvent;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * A sorted set of breaching service names scored by latest detection, plus a hash of first sightings.
 *
 * <p>One ZSET, not one key per service: correlation needs "everything that broke in the last five
 * minutes" as a single range read, and fanning that across per-service keys would turn one round
 * trip into N. The tradeoff is that this becomes a hot key during a large incident, which is exactly
 * the bottleneck named in SCALING.md.
 *
 * <p>Members are service names rather than serialized events. The window is therefore bounded by
 * how many services are broken, not by how many breach events they have produced — see
 * {@link BreachRef}.
 */
@Component
public class RedisCorrelationStore implements CorrelationStore {

    private static final Logger log = LoggerFactory.getLogger(RedisCorrelationStore.class);

    /** Breaching services, scored by their MOST RECENT breach. Decides who is in the window. */
    private static final String KEY = "breaches:recent";

    /**
     * Service to its EARLIEST breach in the current episode. Feeds origin inference only.
     *
     * <p>Two keys because the two questions want opposite answers. "Is this service still breaking?"
     * needs the latest timestamp, or a service drops out of a five-minute read window while it is
     * still on fire. "Which service broke first?" needs the earliest, or every member of a cascade
     * looks equally old and the origin becomes arbitrary.
     *
     * <p>Collapsing them into one score is exactly the regression this replaced: freezing the score
     * at the first breach made every service vanish from its own window after five minutes, and the
     * next heartbeat opened a second incident keyed on itself. Measured, one chain of five produced
     * five incidents 307 seconds apart instead of one.
     */
    private static final String FIRST_SEEN_KEY = "breaches:first-seen";

    private final StringRedisTemplate redis;
    private final Duration retention;

    RedisCorrelationStore(StringRedisTemplate redis, SentinelProperties props) {
        this.redis = redis;
        this.retention = props.getCorrelation().getRecentRetention();
    }

    @Override
    public void record(SloBreachEvent event) {
        long millis = event.detectedAt().toEpochMilli();
        String service = event.serviceName();

        // Members are service names, not serialized events. A service carried one member per SLO
        // per evaluation before, so the window grew with elapsed time rather than with the size of
        // the outage, and every read deserialized all of it. Keyed by service, the window is
        // bounded by how many services are broken.
        redis.opsForZSet().add(KEY, service, millis);

        // First sighting wins and is never overwritten, so a service that has been failing for four
        // minutes keeps its claim to being the origin when its callers start failing behind it.
        redis.opsForHash().putIfAbsent(FIRST_SEEN_KEY, service, Long.toString(millis));

        // Trim on write rather than relying on the key TTL alone: the TTL only fires when the whole
        // key goes idle, and under continuous traffic it never does.
        //
        // Read the expiring members before removing them so the first-seen entries go with them —
        // otherwise a service that recovers and breaks again months later inherits an ancient
        // origin timestamp. Normally this range is empty, so it costs a round trip and nothing else.
        long cutoff = event.detectedAt().minus(retention).toEpochMilli();
        Set<String> expiring = redis.opsForZSet().rangeByScore(KEY, Double.NEGATIVE_INFINITY, cutoff);
        if (expiring != null && !expiring.isEmpty()) {
            redis.opsForZSet().removeRangeByScore(KEY, Double.NEGATIVE_INFINITY, cutoff);
            redis.opsForHash().delete(FIRST_SEEN_KEY, expiring.toArray());
        }

        redis.expire(KEY, retention);
        redis.expire(FIRST_SEEN_KEY, retention);
    }

    @Override
    public List<BreachRef> recentWithin(Duration window, Instant now) {
        double from = now.minus(window).toEpochMilli();
        double to = now.toEpochMilli();

        // Membership is decided by the LATEST breach: still heartbeating means still breaking.
        Set<String> services = redis.opsForZSet().rangeByScore(KEY, from, to);
        if (services == null || services.isEmpty()) {
            return List.of();
        }

        // The timestamp reported is the EARLIEST, because that is the one origin inference orders
        // by. One round trip for all of them rather than one per service.
        List<String> ordered = new ArrayList<>(services);
        List<Object> firstSeen = redis.opsForHash().multiGet(FIRST_SEEN_KEY, List.copyOf(ordered));

        List<BreachRef> refs = new ArrayList<>(ordered.size());
        for (int i = 0; i < ordered.size(); i++) {
            Object raw = i < firstSeen.size() ? firstSeen.get(i) : null;
            long detectedAt;
            try {
                // A missing first-seen means the hash entry expired under a still-live member. Fall
                // back to the window's lower bound: unknown age is old, which keeps the service in
                // the component rather than dropping it.
                detectedAt = raw == null ? (long) from : Long.parseLong(raw.toString());
            } catch (NumberFormatException e) {
                detectedAt = (long) from;
            }
            refs.add(new BreachRef(ordered.get(i), Instant.ofEpochMilli(detectedAt)));
        }
        refs.sort(Comparator.comparing(BreachRef::detectedAt));
        return refs;
    }

    @Override
    public boolean isHealthy() {
        try {
            return "PONG"
                    .equalsIgnoreCase(
                            redis.getRequiredConnectionFactory().getConnection().ping());
        } catch (RuntimeException e) {
            log.warn("redis health check failed: {}", e.toString());
            return false;
        }
    }

}
