package com.sentinel.correlation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.sentinel.events.Topics;
import com.sentinel.support.AbstractIntegrationTest;
import com.sentinel.support.Breaches;
import com.sentinel.support.MutableClock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;

/** Scenario 5: a poison record must leave the partition rather than stall it forever. */
class DeadLetterIT extends AbstractIntegrationTest {

    @Autowired
    private KafkaTemplate<String, Object> kafka;

    @Test
    @DisplayName("a malformed payload lands on the DLT and the consumer keeps working")
    void malformedPayloadIsDeadLetteredWithoutStallingTheConsumer() {
        publishRaw("ledger-service", "this is definitively not json");

        assertThat(awaitDltRecords()).isNotEmpty();

        // The point of dead-lettering rather than retrying forever: the very next valid message on
        // the same partition must still be processed.
        kafka.send(Topics.SLO_BREACH, "ledger-service", Breaches.critical("ledger-service", MutableClock.START));

        await().atMost(AWAIT_TIMEOUT)
                .untilAsserted(() -> assertThat(incidents.count()).isEqualTo(1));
    }

    @Test
    @DisplayName("a structurally valid but wrongly typed payload is also rejected rather than half-applied")
    void wrongShapeIsRejected() {
        publishRaw("ledger-service", "{\"totally\":\"different\",\"shape\":42}");

        // Jackson cannot bind this to SloBreachEvent's required components, so it fails at
        // deserialization. What must not happen is a half-populated incident.
        assertThat(awaitDltRecords()).isNotEmpty();
        assertThat(incidents.count()).isZero();
    }

    private List<String> awaitDltRecords() {
        var records = new java.util.ArrayList<String>();
        try (KafkaConsumer<String, String> consumer = dltConsumer()) {
            consumer.subscribe(List.of(Topics.dlt(Topics.SLO_BREACH)));
            await().atMost(AWAIT_TIMEOUT).untilAsserted(() -> {
                consumer.poll(Duration.ofMillis(500)).forEach(record -> records.add(record.value()));
                assertThat(records).isNotEmpty();
            });
        }
        return records;
    }

    /** Raw bytes, bypassing the JSON serializer, so the failure happens where a real one would. */
    private void publishRaw(String key, String payload) {
        var props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, REDPANDA.getBootstrapServers());
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        try (var producer = new KafkaProducer<String, String>(props)) {
            producer.send(new ProducerRecord<>(Topics.SLO_BREACH, key, payload));
            producer.flush();
        }
    }

    private KafkaConsumer<String, String> dltConsumer() {
        return new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, REDPANDA.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "dlt-inspector-" + System.nanoTime(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName(),
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName()));
    }
}
