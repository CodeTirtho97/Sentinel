package com.sentinel.observability;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import org.apache.kafka.common.TopicPartition;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.actuate.health.Status;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.stereotype.Component;

/**
 * Readiness as "can this pod actually do the job", not "did the process start".
 *
 * <p>A Sentinel replica that is listening on 8080 but holds no partition assignment will serve the
 * incident API perfectly well and consume nothing. Sending it traffic during a rolling update means
 * breaches sit unconsumed for as long as the rebalance takes, which is precisely the window a
 * deployment is most likely to produce them. Default probes cannot see this: the process is up, the
 * context refreshed, the port is open.
 *
 * <p>Contributes to the {@code readiness} group only in effect — see the {@code out-of-service}
 * status mapping in {@code application.yml}. The root health endpoint deliberately stays 200 while
 * this is unready, because losing a partition assignment is a routing decision, not a reason to
 * restart the container.
 *
 * <p>Registered under the bean name {@code kafkaConsumers}, which is the name the readiness group
 * includes.
 */
@Component("kafkaConsumers")
@ConditionalOnProperty(name = "sentinel.events.publisher", havingValue = "kafka", matchIfMissing = true)
public class KafkaReadinessIndicator implements HealthIndicator {

    private final KafkaListenerEndpointRegistry registry;

    KafkaReadinessIndicator(KafkaListenerEndpointRegistry registry) {
        this.registry = registry;
    }

    @Override
    public Health health() {
        Collection<MessageListenerContainer> containers = registry.getListenerContainers();
        if (containers.isEmpty()) {
            return Health.status(Status.OUT_OF_SERVICE)
                    .withDetail("reason", "no listener containers registered")
                    .build();
        }

        Map<String, Object> details = new LinkedHashMap<>();
        boolean ready = true;

        for (MessageListenerContainer container : containers) {
            String id = container.getListenerId();
            if (!container.isRunning()) {
                // Also the shutdown path: containers stop before the context closes, so a draining
                // pod reports unready and leaves the Service's endpoint list before SIGTERM lands.
                details.put(id, "stopped");
                ready = false;
            } else {
                Collection<TopicPartition> assigned = container.getAssignedPartitions();
                if (assigned == null || assigned.isEmpty()) {
                    details.put(id, "no partitions assigned");
                    ready = false;
                } else {
                    details.put(id, assigned.size() + " partitions");
                }
            }
        }

        return (ready ? Health.up() : Health.status(Status.OUT_OF_SERVICE))
                .withDetails(details)
                .build();
    }
}
