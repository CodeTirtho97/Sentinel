package com.sentinel.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ClockConfig {

    /** Injected everywhere a timestamp is taken, so tests can advance time instead of sleeping. */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
