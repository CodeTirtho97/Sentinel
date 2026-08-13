package com.sentinel.events;

import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "sentinel.events.publisher", havingValue = "kafka", matchIfMissing = true)
public class KafkaEventPublisher implements EventPublisher {

    private static final Logger log = LoggerFactory.getLogger(KafkaEventPublisher.class);

    private final KafkaTemplate<String, Object> template;
    private final MeterRegistry registry;

    KafkaEventPublisher(KafkaTemplate<String, Object> template, MeterRegistry registry) {
        this.template = template;
        this.registry = registry;
    }

    @Override
    public void publish(String topic, String key, Object payload) {
        // Async send: a broker hiccup must not stall the 15s evaluation cycle. Failures are counted
        // and logged rather than thrown, because the alternative is the detector taking itself down.
        template.send(topic, key, payload).whenComplete((result, error) -> {
            if (error != null) {
                log.error("publish to {} key={} failed", topic, key, error);
                registry.counter("sentinel.publish.failures", "topic", topic).increment();
            } else {
                registry.counter("sentinel.publish.total", "topic", topic).increment();
            }
        });
    }
}
