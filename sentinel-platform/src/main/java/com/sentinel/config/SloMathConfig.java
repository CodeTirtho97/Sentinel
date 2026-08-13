package com.sentinel.config;

import com.sentinel.slo.domain.Severity;
import com.sentinel.slo.domain.Window;
import com.sentinel.slo.math.BurnRateCalculator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Turns the YAML burn rate table into the pure calculator. */
@Configuration
public class SloMathConfig {

    @Bean
    public List<Window> burnRateWindows(SentinelProperties props) {
        Map<String, SentinelProperties.WindowSpec> configured = props.getSlo().getWindows();
        if (configured.isEmpty()) {
            throw new IllegalStateException("sentinel.slo.windows must define at least one severity");
        }
        return configured.entrySet().stream()
                .map(e -> new Window(
                        Severity.valueOf(e.getKey().toUpperCase(Locale.ROOT)),
                        e.getValue().getLongWindow(),
                        e.getValue().getShortWindow(),
                        e.getValue().getBurnThreshold()))
                .toList();
    }

    @Bean
    public BurnRateCalculator burnRateCalculator(List<Window> burnRateWindows, SentinelProperties props) {
        return new BurnRateCalculator(
                burnRateWindows,
                props.getEvaluation().getMinimumEvents(),
                props.getEvaluation().getMinimumCoverage());
    }

    /** Every distinct window the evaluator has to query, deduplicated across severities. */
    @Bean
    public TreeSet<java.time.Duration> requiredWindows(List<Window> burnRateWindows) {
        return burnRateWindows.stream()
                .flatMap(w -> java.util.stream.Stream.of(w.longWindow(), w.shortWindow()))
                .collect(Collectors.toCollection(TreeSet::new));
    }
}
