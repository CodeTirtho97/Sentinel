package com.sentinel.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.sentinel.slo.domain.Severity;
import com.sentinel.slo.domain.Window;
import com.sentinel.slo.math.BurnRateCalculator;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

/**
 * Binds the real YAML files. A renamed or mistyped key here is invisible until the app starts, and
 * a silently unbound burn rate window would evaluate every SLO against nothing.
 */
class SentinelPropertiesBindingTest {

    private SentinelProperties bind(String... resources) throws IOException {
        var environment = new StandardEnvironment();
        var loader = new YamlPropertySourceLoader();
        // Later files win, matching Spring's profile override order.
        for (String resource : resources) {
            for (PropertySource<?> source : loader.load(resource, new ClassPathResource(resource))) {
                environment.getPropertySources().addFirst(source);
            }
        }
        return Binder.get(environment)
                .bind("sentinel", SentinelProperties.class)
                .get();
    }

    @Test
    void defaultProfileBindsTheProductionBurnRateTable() throws IOException {
        SentinelProperties props = bind("application.yml");

        var windows = props.getSlo().getWindows();
        assertThat(windows).containsOnlyKeys("critical", "high", "medium");

        var critical = windows.get("critical");
        assertThat(critical.getLongWindow()).isEqualTo(Duration.ofHours(1));
        assertThat(critical.getShortWindow()).isEqualTo(Duration.ofMinutes(5));
        assertThat(critical.getBurnThreshold()).isEqualTo(14.4);

        assertThat(windows.get("high").getLongWindow()).isEqualTo(Duration.ofHours(6));
        assertThat(windows.get("medium").getLongWindow()).isEqualTo(Duration.ofDays(3));
    }

    @Test
    void demoProfileCompressesTheWindows() throws IOException {
        SentinelProperties props = bind("application.yml", "application-demo.yml");

        var critical = props.getSlo().getWindows().get("critical");
        assertThat(critical.getLongWindow()).isEqualTo(Duration.ofMinutes(2));
        assertThat(critical.getShortWindow()).isEqualTo(Duration.ofMinutes(1));
        assertThat(critical.getBurnThreshold()).isEqualTo(14.4);

        // A 2m window cannot be 75% covered 45s into a demo.
        assertThat(props.getEvaluation().getMinimumCoverage()).isEqualTo(0.3);
    }

    @Test
    void everyBoundWindowSurvivesDomainValidation() throws IOException {
        for (String profile : List.of("application.yml", "application-demo.yml")) {
            SentinelProperties props =
                    profile.equals("application.yml") ? bind("application.yml") : bind("application.yml", profile);

            List<Window> windows = new SloMathConfig().burnRateWindows(props);

            assertThat(windows).hasSize(3);
            assertThat(windows)
                    .extracting(Window::severity)
                    .containsExactlyInAnyOrder(Severity.CRITICAL, Severity.HIGH, Severity.MEDIUM);
        }
    }

    @Test
    void calculatorIsConstructableFromTheDefaultConfiguration() throws IOException {
        SentinelProperties props = bind("application.yml");
        var config = new SloMathConfig();

        List<Window> windows = config.burnRateWindows(props);
        BurnRateCalculator calculator = config.burnRateCalculator(windows, props);

        assertThat(calculator).isNotNull();
        assertThat(config.requiredWindows(windows))
                .containsExactly(
                        Duration.ofMinutes(5),
                        Duration.ofMinutes(30),
                        Duration.ofHours(1),
                        Duration.ofHours(6),
                        Duration.ofDays(3));
    }

    @Test
    void dependencyGraphBindsTheFleetTopology() throws IOException {
        SentinelProperties props = bind("application.yml");

        // Order path, entry point down to the leaf the cascade demo breaks.
        assertThat(props.getDependencies())
                .containsEntry("api-gateway", List.of("checkout-service"))
                .containsEntry("checkout-service", List.of("cart-service", "payment-service"))
                .containsEntry("cart-service", List.of("payment-service"))
                .containsEntry("payment-service", List.of("fraud-service"))
                .containsEntry("fraud-service", List.of("ledger-service"));
        assertThat(props.getDependencies().get("ledger-service")).isEmpty();

        // Browse path, deliberately disconnected from the order path.
        assertThat(props.getDependencies()).containsEntry("search-service", List.of("catalog-service"));
        assertThat(props.getDependencies().get("catalog-service")).isEmpty();
    }

    @Test
    void theTwoTreesShareNoService() {
        // The negative demo depends on this: break a leaf in each tree and the platform must report
        // two incidents. A single shared edge would silently merge them into one and the test that
        // proves correlation is not just grouping everything would start passing for the wrong
        // reason.
        var order = Set.of(
                "api-gateway",
                "checkout-service",
                "cart-service",
                "payment-service",
                "fraud-service",
                "ledger-service");
        var browse = Set.of("search-service", "catalog-service");

        SentinelProperties props = bindQuietly("application.yml");
        props.getDependencies().forEach((service, callees) -> {
            boolean fromOrder = order.contains(service);
            callees.forEach(callee -> assertThat(fromOrder ? order.contains(callee) : browse.contains(callee))
                    .as("%s -> %s crosses the two trees", service, callee)
                    .isTrue());
        });

        assertThat(props.getDependencies().keySet()).containsExactlyInAnyOrderElementsOf(union(order, browse));
    }

    private static Set<String> union(Set<String> a, Set<String> b) {
        var all = new java.util.LinkedHashSet<>(a);
        all.addAll(b);
        return all;
    }

    private SentinelProperties bindQuietly(String resource) {
        try {
            return bind(resource);
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }
}
