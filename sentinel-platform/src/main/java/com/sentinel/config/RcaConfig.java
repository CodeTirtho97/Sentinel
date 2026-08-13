package com.sentinel.config;

import com.sentinel.rca.LlmChatCaller;
import com.sentinel.rca.RcaDrafter;
import com.sentinel.rca.SpringAiRcaDrafter;
import com.sentinel.rca.TemplateRcaDrafter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.util.StreamUtils;

/**
 * Wires the RCA seam, and decides whether a model is involved at all.
 *
 * <p>{@code make demo} must work with no API key — a reviewer will not sign up for Groq to try the
 * project. That constraint drives the shape of this class.
 */
@Configuration
public class RcaConfig {

    private static final Logger log = LoggerFactory.getLogger(RcaConfig.class);

    /**
     * Placeholder standing in for an unset {@code LLM_API_KEY}.
     *
     * <p>Spring AI asserts a non-blank key while building its client and fails the context if it
     * finds one missing, so "no key" cannot simply be an empty property — the application would not
     * start. A recognisable placeholder keeps autoconfiguration satisfied and gives this class a
     * reliable way to tell "unset" from "set to something".
     */
    public static final String UNCONFIGURED_KEY = "not-configured";

    /**
     * The guarded call to the model. A bean, so Resilience4j has a proxy to wrap.
     *
     * <p>It owns its own virtual-thread executor rather than taking one as a bean: exposing any
     * {@code Executor} bean would trip {@code @ConditionalOnMissingBean(Executor.class)} in Boot's
     * task-execution autoconfiguration and quietly replace {@code applicationTaskExecutor} for the
     * whole application, which is a large blast radius for a thread pool that makes one call per
     * incident.
     */
    @Bean
    LlmChatCaller llmChatCaller(ChatModel chatModel) {
        return new LlmChatCaller(ChatClient.builder(chatModel).build());
    }

    /**
     * The single {@link RcaDrafter} the rest of the application sees.
     *
     * <p>With no key configured this returns the template drafter outright rather than letting the
     * circuit breaker discover the problem. The circuit-breaker route also "works", but it spends
     * three retries and a timeout per incident learning something already knowable at startup, and
     * fills the demo's logs with provider stack traces at precisely the moment someone is watching.
     * The breaker still earns its keep for the case it is actually for: a key that is present and a
     * provider that is failing.
     */
    @Bean
    RcaDrafter rcaDrafter(
            LlmChatCaller caller,
            @Value("${spring.ai.openai.api-key:}") String apiKey,
            @Value("${spring.ai.openai.chat.options.model:unknown}") String model,
            @Value("classpath:prompts/rca-system.st") Resource systemPrompt) {

        var template = new TemplateRcaDrafter();

        if (apiKey.isBlank() || UNCONFIGURED_KEY.equals(apiKey)) {
            log.info("no LLM API key configured — RCA will use the deterministic timeline summary. "
                    + "Set LLM_API_KEY to enable model-drafted hypotheses.");
            return template;
        }

        log.info("RCA drafting via model '{}'", model);
        return new SpringAiRcaDrafter(caller, template, read(systemPrompt), model);
    }

    private static String read(Resource resource) {
        try {
            return StreamUtils.copyToString(resource.getInputStream(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            // The prompt is packaged in the jar. Missing means a broken build, not a runtime
            // condition to degrade around.
            throw new UncheckedIOException("cannot read RCA system prompt", e);
        }
    }
}
