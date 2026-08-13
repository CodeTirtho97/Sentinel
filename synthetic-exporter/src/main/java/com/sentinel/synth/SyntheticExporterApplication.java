package com.sentinel.synth;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * Load testing only. Never part of the demo.
 *
 * <p>Exists so "the evaluator handles 1000 services" is a measurement rather than a claim. The
 * evaluator queries Prometheus for time series and has no idea what produced them, so a thousand
 * services can be a thousand series from one process instead of a thousand containers and 32GB of
 * RAM. The load-test knob becomes a single environment variable.
 *
 * <p>The four real fleet services stay for the demo — they cascade properly and tell the story.
 * This module only ever stress-tests.
 */
@SpringBootApplication
@EnableConfigurationProperties(SyntheticProperties.class)
public class SyntheticExporterApplication {

    public static void main(String[] args) {
        SpringApplication.run(SyntheticExporterApplication.class, args);
    }
}
