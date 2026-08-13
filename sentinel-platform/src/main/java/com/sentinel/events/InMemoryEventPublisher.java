package com.sentinel.events;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Records instead of sending, so a test can assert on what the evaluator decided without standing
 * up a broker. Activated with {@code sentinel.events.publisher=in-memory}.
 */
@Component
@ConditionalOnProperty(name = "sentinel.events.publisher", havingValue = "in-memory")
public class InMemoryEventPublisher implements EventPublisher {

    public record Published(String topic, String key, Object payload) {}

    private final List<Published> published = new CopyOnWriteArrayList<>();

    @Override
    public void publish(String topic, String key, Object payload) {
        published.add(new Published(topic, key, payload));
    }

    public List<Published> published() {
        return List.copyOf(published);
    }

    @SuppressWarnings("unchecked")
    public <T> List<T> payloads(String topic, Class<T> type) {
        List<T> result = new ArrayList<>();
        for (Published p : published) {
            if (p.topic().equals(topic) && type.isInstance(p.payload())) {
                result.add((T) p.payload());
            }
        }
        return result;
    }
}
