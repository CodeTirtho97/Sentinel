package com.sentinel.correlation;

import com.fasterxml.jackson.databind.ObjectMapper;
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
 * A single sorted set scored by detection time.
 *
 * <p>One ZSET, not one key per service: correlation needs "everything that broke in the last five
 * minutes" as a single range read, and fanning that across per-service keys would turn one round
 * trip into N. The tradeoff is that this becomes a hot key during a large incident, which is exactly
 * the bottleneck named in SCALING.md.
 */
@Component
public class RedisCorrelationStore implements CorrelationStore {

    private static final Logger log = LoggerFactory.getLogger(RedisCorrelationStore.class);

    private static final String KEY = "breaches:recent";

    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;
    private final Duration retention;

    RedisCorrelationStore(StringRedisTemplate redis, ObjectMapper mapper, SentinelProperties props) {
        this.redis = redis;
        this.mapper = mapper;
        this.retention = props.getCorrelation().getRecentRetention();
    }

    @Override
    public void record(SloBreachEvent event) {
        String member = serialize(event);
        // Members are the serialized event, so a redelivered event is the same member and ZADD
        // updates its score rather than adding a second copy.
        redis.opsForZSet().add(KEY, member, event.detectedAt().toEpochMilli());

        // Trim on write rather than relying on the key TTL alone: the TTL only fires when the whole
        // key goes idle, and under continuous traffic it never does.
        long cutoff = event.detectedAt().minus(retention).toEpochMilli();
        redis.opsForZSet().removeRangeByScore(KEY, Double.NEGATIVE_INFINITY, cutoff);
        redis.expire(KEY, retention);
    }

    @Override
    public List<SloBreachEvent> recentWithin(Duration window, Instant now) {
        double from = now.minus(window).toEpochMilli();
        double to = now.toEpochMilli();
        Set<String> members = redis.opsForZSet().rangeByScore(KEY, from, to);
        if (members == null || members.isEmpty()) {
            return List.of();
        }

        List<SloBreachEvent> events = new ArrayList<>(members.size());
        for (String member : members) {
            try {
                events.add(mapper.readValue(member, SloBreachEvent.class));
            } catch (Exception e) {
                // A member written by an older schema must not take down correlation for the ones
                // that are still readable.
                log.warn("skipping unreadable correlation member: {}", e.toString());
            }
        }
        events.sort(Comparator.comparing(SloBreachEvent::detectedAt));
        return events;
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

    private String serialize(SloBreachEvent event) {
        try {
            return mapper.writeValueAsString(event);
        } catch (Exception e) {
            throw new IllegalStateException("cannot serialize breach event " + event.eventId(), e);
        }
    }
}
