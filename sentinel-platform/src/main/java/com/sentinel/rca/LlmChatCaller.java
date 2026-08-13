package com.sentinel.rca;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import io.github.resilience4j.timelimiter.annotation.TimeLimiter;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;

/**
 * The guarded call to the model, and the only place in the codebase that talks to one.
 *
 * <p>Its own bean for a mundane but load-bearing reason: Resilience4j works through a proxy, and a
 * drafter calling its own annotated method would bypass every annotation on it. Splitting the call
 * out puts a real proxy boundary between the drafter and the network.
 *
 * <p>The return type is {@link CompletableFuture} because {@code @TimeLimiter} requires a
 * {@code CompletionStage} — applied to a method returning {@code String} it fails at runtime, not
 * at compile time, which is a genuinely unpleasant way to find out.
 *
 * <p>Aspect order is Resilience4j's default: Retry wraps CircuitBreaker wraps TimeLimiter. So each
 * attempt gets its own timeout, a timed-out attempt counts as a circuit-breaker failure, and the
 * retries happen outside both.
 */
public class LlmChatCaller implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(LlmChatCaller.class);

    private final ChatClient chatClient;

    /**
     * Blocking HTTP on virtual threads: the natural fit for a call that spends all its time waiting.
     *
     * <p>Owned rather than injected. A {@code TimeLimiter} timeout cancels the future but cannot
     * stop the HTTP call already in flight, so the thread stays parked until the socket gives up —
     * survivable on virtual threads, and something that would slowly consume a shared pool.
     */
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public LlmChatCaller(ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    @Override
    public void close() {
        executor.shutdown();
    }

    /**
     * Sends the prompt and returns the completion.
     *
     * <p>No {@code fallbackMethod}. The failure is handled one level up in {@link
     * SpringAiRcaDrafter}, where the deterministic summary is already to hand — a fallback here
     * would have to return a failed future or a magic string for that layer to unpick again. The
     * circuit breaker still records the failure and still short-circuits; only the recovery moved.
     */
    @CircuitBreaker(name = "llm")
    @Retry(name = "llm")
    @TimeLimiter(name = "llm")
    public CompletableFuture<String> complete(String systemPrompt, String userPrompt) {
        return CompletableFuture.supplyAsync(
                () -> {
                    log.debug("calling model, prompt {} chars", userPrompt.length());
                    return chatClient
                            .prompt()
                            .system(systemPrompt)
                            .user(userPrompt)
                            .call()
                            .content();
                },
                executor);
    }
}
