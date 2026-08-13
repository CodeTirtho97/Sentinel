package com.sentinel.events;

/**
 * Seam: lets integration tests run without a broker, and is the only place the rest of the codebase
 * touches messaging.
 */
public interface EventPublisher {

    /**
     * Publishes to {@code topic} under {@code key}.
     *
     * <p>The key choice is load-bearing, not incidental: breaches key on service name so one
     * service's breaches stay ordered within a partition, and incident events key on incident id.
     */
    void publish(String topic, String key, Object payload);
}
