package com.sentinel.config;

import com.sentinel.events.Topics;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.validation.ValidationException;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ConsumerRecordRecoverer;
import org.springframework.kafka.listener.ContainerProperties.AckMode;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;
import org.springframework.kafka.support.serializer.DeserializationException;

@Configuration
@EnableKafka
@ConditionalOnProperty(name = "sentinel.events.publisher", havingValue = "kafka", matchIfMissing = true)
public class KafkaConfig {

    private static final Logger log = LoggerFactory.getLogger(KafkaConfig.class);

    private static final int PARTITIONS = 3;

    /** Keyed by serviceName: one service's breaches share a partition, so their order is preserved. */
    @Bean
    org.apache.kafka.clients.admin.NewTopic sloBreachTopic() {
        return TopicBuilder.name(Topics.SLO_BREACH)
                .partitions(PARTITIONS)
                .replicas(1)
                .build();
    }

    @Bean
    org.apache.kafka.clients.admin.NewTopic incidentOpenedTopic() {
        return TopicBuilder.name(Topics.INCIDENT_OPENED)
                .partitions(PARTITIONS)
                .replicas(1)
                .build();
    }

    @Bean
    org.apache.kafka.clients.admin.NewTopic incidentStateChangedTopic() {
        return TopicBuilder.name(Topics.INCIDENT_STATE_CHANGED)
                .partitions(PARTITIONS)
                .replicas(1)
                .build();
    }

    // Dead letter topics exist for manual inspection, so ordering and throughput do not matter.
    @Bean
    org.apache.kafka.clients.admin.NewTopic sloBreachDlt() {
        return TopicBuilder.name(Topics.dlt(Topics.SLO_BREACH))
                .partitions(1)
                .replicas(1)
                .build();
    }

    @Bean
    org.apache.kafka.clients.admin.NewTopic incidentOpenedDlt() {
        return TopicBuilder.name(Topics.dlt(Topics.INCIDENT_OPENED))
                .partitions(1)
                .replicas(1)
                .build();
    }

    @Bean
    org.apache.kafka.clients.admin.NewTopic incidentStateChangedDlt() {
        return TopicBuilder.name(Topics.dlt(Topics.INCIDENT_STATE_CHANGED))
                .partitions(1)
                .replicas(1)
                .build();
    }

    /**
     * Manual ack: the offset is committed only after the database transaction has committed. A pod
     * evicted mid-message leaves the offset uncommitted, the group rebalances, and another consumer
     * reprocesses — which the idempotency layers make safe.
     */
    @Bean
    ConcurrentKafkaListenerContainerFactory<String, Object> kafkaListenerContainerFactory(
            ConsumerFactory<String, Object> consumerFactory, DefaultErrorHandler errorHandler) {
        var factory = new ConcurrentKafkaListenerContainerFactory<String, Object>();
        factory.setConsumerFactory(consumerFactory);
        factory.setCommonErrorHandler(errorHandler);
        factory.getContainerProperties().setAckMode(AckMode.MANUAL);
        return factory;
    }

    @Bean
    DefaultErrorHandler errorHandler(KafkaTemplate<String, Object> template, MeterRegistry registry) {
        // The DLT is declared with one partition, so the destination must not copy the source
        // partition number — a record from partition 2 would otherwise be sent to a partition that
        // does not exist. -1 lets the producer pick.
        var recoverer = new DeadLetterPublishingRecoverer(
                template, (rec, ex) -> new TopicPartition(Topics.dlt(rec.topic()), -1));

        ConsumerRecordRecoverer counted = (rec, ex) -> {
            log.error("routing {}-{}@{} to DLT: {}", rec.topic(), rec.partition(), rec.offset(), ex.toString());
            registry.counter("sentinel.consumer.dlt", "topic", rec.topic()).increment();
            recoverer.accept(rec, ex);
        };

        var backOff = new ExponentialBackOffWithMaxRetries(3);
        backOff.setInitialInterval(1_000L);
        backOff.setMultiplier(2.0);

        var handler = new DefaultErrorHandler(counted, backOff);
        // A payload that cannot be parsed or is structurally invalid will never parse on retry.
        // Retrying it only delays the DLT and blocks the partition behind it.
        handler.addNotRetryableExceptions(DeserializationException.class, ValidationException.class);
        handler.setRetryListeners((rec, ex, attempt) -> {
            log.warn("retry {} for {}-{}@{}: {}", attempt, rec.topic(), rec.partition(), rec.offset(), ex.toString());
            registry.counter("sentinel.consumer.retry", "topic", rec.topic()).increment();
        });
        return handler;
    }
}
