package com.sentinel.fleet;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.actuate.autoconfigure.metrics.MeterRegistryCustomizer;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/** One app run as four instances: checkout, cart, payment, ledger. */
@SpringBootApplication
@EnableConfigurationProperties(FleetProperties.class)
public class FleetApplication {

    public static void main(String[] args) {
        SpringApplication.run(FleetApplication.class, args);
    }

    /** Tags every metric with {@code service=<name>}, the label the recording rules aggregate on. */
    @Bean
    MeterRegistryCustomizer<MeterRegistry> serviceTag(FleetProperties props) {
        return registry -> registry.config().commonTags("service", props.getServiceName());
    }

    /** Short read timeout so a hung downstream fails fast upstream instead of blocking callers. */
    @Bean
    RestClient downstreamClient() {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(2));
        factory.setReadTimeout(Duration.ofSeconds(5));
        return RestClient.builder().requestFactory(factory).build();
    }
}
