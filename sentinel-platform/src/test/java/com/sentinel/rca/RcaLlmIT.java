package com.sentinel.rca;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.sentinel.incident.Incident;
import com.sentinel.incident.IncidentEventLog;
import com.sentinel.incident.IncidentService;
import com.sentinel.slo.domain.Severity;
import com.sentinel.slo.domain.SloType;
import com.sentinel.support.AbstractIntegrationTest;
import com.sentinel.support.Breaches;
import com.sentinel.support.MutableClock;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Scenario 6: the LLM boundary, and what happens when it misbehaves.
 *
 * <p>WireMock rather than a live provider, because the interesting cases are the failures — a 500,
 * a 429, an empty body — and those are not things you can ask Groq for on demand. It also means the
 * suite runs offline, which the CI job depends on.
 *
 * <p>An LLM failure deliberately does <b>not</b> dead-letter. Retries are exhausted, the
 * deterministic summary is written instead, and the message is acked: the incident keeps its draft
 * and the consumer keeps moving. Dead-lettering here would mean an incident with no RCA at all,
 * which is strictly worse than one with a plain timeline.
 */
class RcaLlmIT extends AbstractIntegrationTest {

    private static final String COMPLETIONS = "/v1/chat/completions";

    /**
     * HTTP/1.1 only.
     *
     * <p>Left on HTTP/2, WireMock and the JDK HTTP client the Spring AI RestClient sits on abort
     * response bodies with {@code RST_STREAM: Stream cancelled}. Error responses survive it — which
     * makes it a genuinely confusing failure, since the retry and fallback tests pass while the
     * success path silently falls back. Nothing here is testing protocol negotiation.
     */
    private static final WireMockServer LLM =
            new WireMockServer(WireMockConfiguration.options().dynamicPort().http2PlainDisabled(true));

    static {
        LLM.start();
    }

    @DynamicPropertySource
    static void llmProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.ai.openai.base-url", LLM::baseUrl);
        // A real-looking key, so RcaConfig wires the model-backed drafter rather than recognising
        // the placeholder and short-circuiting to the template.
        registry.add("spring.ai.openai.api-key", () -> "test-key");
        // The production 2s backoff would put twelve seconds of sleeping in this class. What is
        // under test is that the retries happen at all, not how patiently they wait.
        registry.add("resilience4j.retry.instances.llm.wait-duration", () -> "20ms");
        registry.add("resilience4j.timelimiter.instances.llm.timeout-duration", () -> "5s");
    }

    @AfterAll
    static void stopWireMock() {
        LLM.stop();
    }

    @Autowired
    private RcaService rca;

    @Autowired
    private IncidentService incidentService;

    @Autowired
    private CircuitBreakerRegistry circuitBreakers;

    private TransactionTemplate transactions;

    @Autowired
    void buildTransactionTemplate(PlatformTransactionManager txManager) {
        this.transactions = new TransactionTemplate(txManager);
    }

    @BeforeEach
    void resetLlm() {
        LLM.resetAll();
        // Failures from a previous test would otherwise leave the breaker open and the next test
        // would never reach WireMock at all — a confusing way to fail.
        circuitBreakers.circuitBreaker("llm").reset();
    }

    @Test
    @DisplayName("a healthy model produces a model-attributed draft")
    void modelDraftIsStored() {
        LLM.stubFor(post(urlPathEqualTo(COMPLETIONS)).willReturn(completion("SUMMARY\nledger-service is the origin.")));

        UUID id = openIncident();
        assertThat(rca.draftFor(id, false)).isTrue();

        Incident incident = incidentService.get(id);
        assertThat(incident.getRcaDraft()).contains("ledger-service is the origin.");
        assertThat(incident.getRcaFallback()).isFalse();
        assertThat(incident.getRcaModel()).isNotEqualTo(RcaDraft.TEMPLATE_MODEL);
    }

    @Test
    @DisplayName("a persistently failing model is retried, then falls back — it does not dead-letter")
    void serverErrorsRetryThenFallBack() {
        LLM.stubFor(post(urlPathEqualTo(COMPLETIONS)).willReturn(aResponse().withStatus(500)));

        UUID id = openIncident();
        assertThat(rca.draftFor(id, false)).isTrue();

        // Three attempts, per resilience4j.retry.instances.llm.max-attempts.
        LLM.verify(3, postRequestedFor(urlPathEqualTo(COMPLETIONS)));

        Incident incident = incidentService.get(id);
        assertThat(incident.getRcaFallback()).isTrue();
        assertThat(incident.getRcaModel()).isEqualTo(RcaDraft.TEMPLATE_MODEL);
        // The whole point: the incident is still explained, by the deterministic summary.
        assertThat(incident.getRcaDraft()).contains("LIKELY ORIGIN").contains("ledger-service");
    }

    @Test
    @DisplayName("a rate-limited model falls back rather than losing the incident's draft")
    void rateLimitFallsBack() {
        LLM.stubFor(post(urlPathEqualTo(COMPLETIONS))
                .willReturn(aResponse().withStatus(429).withHeader("Content-Type", "application/json")));

        UUID id = openIncident();
        rca.draftFor(id, false);

        assertThat(incidentService.get(id).getRcaFallback()).isTrue();
    }

    @Test
    @DisplayName("a 200 carrying no content is treated as a failure, not stored as an empty RCA")
    void emptyCompletionFallsBack() {
        LLM.stubFor(post(urlPathEqualTo(COMPLETIONS)).willReturn(completion("   ")));

        UUID id = openIncident();
        rca.draftFor(id, false);

        Incident incident = incidentService.get(id);
        assertThat(incident.getRcaFallback()).isTrue();
        assertThat(incident.getRcaDraft()).contains("SUMMARY");
    }

    @Test
    @DisplayName("the circuit opens after repeated failures and stops calling the provider")
    void circuitBreakerStopsCallingAFailingProvider() {
        LLM.stubFor(post(urlPathEqualTo(COMPLETIONS)).willReturn(aResponse().withStatus(500)));

        // Enough drafts to fill the breaker's minimum-number-of-calls with failures.
        for (int i = 0; i < 4; i++) {
            rca.draftFor(openIncident("origin-" + i + "-" + UUID.randomUUID()), false);
        }
        int callsBeforeOpen = LLM.getAllServeEvents().size();

        UUID afterOpen = openIncident("after-open-" + UUID.randomUUID());
        rca.draftFor(afterOpen, false);

        // Short-circuited: the provider is not called again while the breaker is open.
        assertThat(LLM.getAllServeEvents()).hasSize(callsBeforeOpen);
        // And the incident is still explained.
        assertThat(incidentService.get(afterOpen).getRcaFallback()).isTrue();
    }

    @Test
    @DisplayName("regeneration replaces an existing draft; a plain draft request does not")
    void regenerationForcesARedraft() {
        LLM.stubFor(post(urlPathEqualTo(COMPLETIONS)).willReturn(completion("SUMMARY\nfirst draft")));
        UUID id = openIncident();
        rca.draftFor(id, false);

        // Not forced: the existing draft stands and the model is not called again.
        LLM.resetRequests();
        assertThat(rca.draftFor(id, false)).isFalse();
        LLM.verify(0, postRequestedFor(urlPathEqualTo(COMPLETIONS)));
        assertThat(incidentService.get(id).getRcaDraft()).contains("first draft");

        LLM.stubFor(post(urlPathEqualTo(COMPLETIONS)).willReturn(completion("SUMMARY\nsecond draft")));
        assertThat(rca.draftFor(id, true)).isTrue();
        assertThat(incidentService.get(id).getRcaDraft()).contains("second draft");
    }

    /**
     * A fresh incident under a correlation key nothing else in the suite uses.
     *
     * <p>Not cosmetic. {@code openOrAttach} is idempotent on the correlation key, so a plain
     * {@code ledger-service} would silently <i>attach</i> to any active incident another test class
     * left behind — returning an id that already carries a draft, and making
     * {@code draftFor(id, false)} correctly return false for a reason that has nothing to do with
     * what is under test. The name still starts with {@code ledger-service} so the assertions about
     * the origin appearing in the draft read the same.
     */
    private UUID openIncident() {
        return openIncident("ledger-service-" + UUID.randomUUID());
    }

    /**
     * Builds the incident straight through the repository, deliberately bypassing
     * {@code IncidentService.openOrAttach}.
     *
     * <p>{@code openOrAttach} publishes {@code incident.opened.v1} on commit, and the RCA consumer
     * then drafts the very incident this test is about to draft by hand. That race is not
     * theoretical and not fixable by stopping the listener around each test — the gap between one
     * test's restart and the next test's stop is long enough for it to drain a backlog. It fails in
     * two ways at once: the test's own {@code draftFor} finds a draft already present and returns
     * false, and the consumer's extra calls fill the circuit breaker's sliding window so that the
     * tests needing a <i>working</i> model get short-circuited to the fallback instead.
     *
     * <p>Removing the publish removes the race by construction. Nothing is lost: the consumer path
     * is what {@code RcaConsumerIT} exists for, and this class is about the drafter.
     */
    private UUID openIncident(String origin) {
        return transactions.execute(status -> {
            var event = Breaches.critical(origin, MutableClock.START);
            incidents.insertIfAbsent(
                    UUID.randomUUID(),
                    origin,
                    Severity.CRITICAL.name(),
                    origin,
                    MutableClock.START,
                    MutableClock.START);
            UUID incidentId = incidents
                    .lockActiveIdByCorrelationKey(origin)
                    .orElseThrow(() -> new IllegalStateException("no incident for " + origin));

            eventLog.save(IncidentEventLog.breach(
                    incidentId,
                    event.eventId(),
                    origin,
                    SloType.AVAILABILITY,
                    Severity.CRITICAL,
                    22.1,
                    18.4,
                    MutableClock.START));

            // insertIfAbsent writes the incident row but not its affected-services collection, and
            // the drafter reads that to build the blast radius.
            Incident incident = incidents.findById(incidentId).orElseThrow();
            incident.recordBreach(Severity.CRITICAL, Set.of(origin), MutableClock.START);
            incidents.save(incident);

            return incidentId;
        });
    }

    /** The slice of the OpenAI chat-completions response Spring AI actually reads. */
    private static com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder completion(String content) {
        return aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody(
                        """
                        {
                          "id": "chatcmpl-test",
                          "object": "chat.completion",
                          "created": 1770000000,
                          "model": "llama-3.3-70b-versatile",
                          "choices": [
                            {
                              "index": 0,
                              "message": { "role": "assistant", "content": "%s" },
                              "finish_reason": "stop"
                            }
                          ],
                          "usage": { "prompt_tokens": 120, "completion_tokens": 60, "total_tokens": 180 }
                        }
                        """
                                .formatted(content.replace("\n", "\\n")));
    }
}
