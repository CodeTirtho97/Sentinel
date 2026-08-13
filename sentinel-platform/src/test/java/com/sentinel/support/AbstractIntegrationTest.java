package com.sentinel.support;

import com.sentinel.correlation.DependencyGraph;
import com.sentinel.correlation.ServiceDependency;
import com.sentinel.correlation.ServiceDependencyRepository;
import com.sentinel.incident.IncidentEventLog;
import com.sentinel.incident.IncidentEventLogRepository;
import com.sentinel.incident.IncidentRepository;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.redpanda.RedpandaContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Real Postgres, real Redpanda, real Redis.
 *
 * <p>Mocked brokers and in-memory databases hide exactly the bugs this suite exists to catch:
 * serialization mismatches, partial-index syntax, rebalance behaviour, manual-ack ordering.
 *
 * <p>The containers are static and started once for the JVM rather than per class. Per-class
 * lifecycle would put a Redpanda start on the front of every test class and turn the suite into a
 * ten-minute wait.
 *
 * <p>{@code @AutoConfigureObservability} is not decoration. Spring Boot switches metrics exporters
 * off under {@code @SpringBootTest}, which silently removes {@code /actuator/prometheus} — so
 * without this the endpoint Prometheus scrapes and Grafana draws from is the one thing in the
 * system no test ever touches. Declared on the base class rather than on the one test that noticed,
 * so every integration test shares a single application context instead of forking a second one.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureObservability
@ActiveProfiles("test")
@Import(TestClockConfig.class)
public abstract class AbstractIntegrationTest {

    protected static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"));

    protected static final RedpandaContainer REDPANDA =
            new RedpandaContainer(DockerImageName.parse("redpandadata/redpanda:v24.1.7"));

    protected static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine")).withExposedPorts(6379);

    static {
        POSTGRES.start();
        REDPANDA.start();
        REDIS.start();
    }

    @DynamicPropertySource
    static void containerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.kafka.bootstrap-servers", REDPANDA::getBootstrapServers);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    protected static final Duration AWAIT_TIMEOUT = Duration.ofSeconds(30);

    /**
     * Attaches the API key to every {@code TestRestTemplate} call.
     *
     * <p>{@code TestRestTemplate} is a context singleton while this method runs once per test
     * instance, hence the guard — without it the same interceptor stacks up on every test and the
     * header is sent a hundred times over.
     *
     * <p>Done centrally rather than per call so that a test asserting on an endpoint's behaviour is
     * not also, incidentally, asserting that someone remembered a header.
     */
    @Autowired
    void authenticateRestTemplate(TestRestTemplate rest, @Value("${sentinel.api-key}") String apiKey) {
        List<ClientHttpRequestInterceptor> interceptors = rest.getRestTemplate().getInterceptors();
        if (interceptors.stream().noneMatch(i -> i instanceof ApiKeyInterceptor)) {
            interceptors.add(new ApiKeyInterceptor(apiKey));
        }
    }

    /** Named rather than a lambda purely so the guard above can recognise it. */
    private record ApiKeyInterceptor(String apiKey) implements ClientHttpRequestInterceptor {
        @Override
        public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
                throws IOException {
            request.getHeaders().add("X-Api-Key", apiKey);
            return execution.execute(request, body);
        }
    }

    @Autowired
    protected IncidentRepository incidents;

    @Autowired
    protected IncidentEventLogRepository eventLog;

    @Autowired
    protected ServiceDependencyRepository dependencies;

    @Autowired
    protected DependencyGraph graph;

    @Autowired
    protected StringRedisTemplate redis;

    @Autowired
    protected MutableClock clock;

    /**
     * State that leaks between tests here is state that makes a failure impossible to read, so
     * every store is truthfully empty at the start of each test — including Redis, whose dedupe
     * keys would otherwise silently skip the next test's events.
     */
    @BeforeEach
    void resetState() {
        eventLog.deleteAllInBatch();
        incidents.deleteAllInBatch();
        redis.getConnectionFactory().getConnection().serverCommands().flushAll();
        clock.reset();
        seedFleetTopology();
    }

    /**
     * Breach entries only, across every incident.
     *
     * <p>The timeline also carries lifecycle entries and the RCA entry the drafter writes when an
     * incident opens. A test making a claim about breaches — "nothing lost, nothing duplicated" —
     * has to count breaches, or it fails the next time anything else legitimately writes a row.
     */
    protected List<IncidentEventLog> breachEntries() {
        return eventLog.findAll().stream()
                .filter(entry -> entry.getKind() == IncidentEventLog.Kind.BREACH)
                .toList();
    }

    /** Breach entries belonging to one incident. */
    protected List<IncidentEventLog> breachEntries(UUID incidentId) {
        return eventLog.findByIncidentIdOrderByOccurredAtAsc(incidentId).stream()
                .filter(entry -> entry.getKind() == IncidentEventLog.Kind.BREACH)
                .toList();
    }

    /** checkout -> cart, payment; cart -> payment; payment -> ledger. */
    protected void seedFleetTopology() {
        dependencies.deleteAllInBatch();
        dependencies.saveAll(List.of(
                new ServiceDependency("checkout-service", "cart-service"),
                new ServiceDependency("checkout-service", "payment-service"),
                new ServiceDependency("cart-service", "payment-service"),
                new ServiceDependency("payment-service", "ledger-service")));
        dependencies.flush();
        graph.refresh();
    }
}
