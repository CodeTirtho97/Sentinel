package com.sentinel.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Replaces the system clock everywhere at once.
 *
 * <p>Marked primary rather than overriding the bean definition, so the production {@code ClockConfig}
 * stays exactly as it ships and the test wiring is visible instead of implicit.
 */
@TestConfiguration
public class TestClockConfig {

    @Bean
    @Primary
    MutableClock mutableClock() {
        return new MutableClock();
    }
}
