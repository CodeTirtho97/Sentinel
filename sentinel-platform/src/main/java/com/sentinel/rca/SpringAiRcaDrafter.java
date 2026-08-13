package com.sentinel.rca;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Drafts with a model, and falls back to the deterministic summary when it cannot.
 *
 * <p>The fallback is the feature, not the error path. An incident with a plain timeline is still a
 * correlated incident with a blast radius and an inferred origin; the model adds a narrative on top
 * of that. Framed the other way round — LLM required, degraded when absent — the observability
 * platform would have taken a dependency on a third party's uptime in order to explain outages,
 * which is the wrong way round.
 *
 * <p>Timing and the fallback counter live in {@link RcaService}, so they are recorded identically
 * whichever drafter is wired in. {@link RcaDraft#fallback()} is what carries the signal up.
 */
public class SpringAiRcaDrafter implements RcaDrafter {

    private static final Logger log = LoggerFactory.getLogger(SpringAiRcaDrafter.class);

    private final LlmChatCaller caller;
    private final RcaDrafter fallback;
    private final String systemPrompt;
    private final String model;

    public SpringAiRcaDrafter(LlmChatCaller caller, RcaDrafter fallback, String systemPrompt, String model) {
        this.caller = caller;
        this.fallback = fallback;
        this.systemPrompt = systemPrompt;
        this.model = model;
    }

    @Override
    public RcaDraft draft(IncidentContext context) {
        try {
            String text = caller.complete(systemPrompt, TimelineBuilder.render(context))
                    .join();

            if (text == null || text.isBlank()) {
                // A 200 carrying nothing is a failure that does not throw. Treated as one, or the
                // incident ends up with an empty RCA and no indication anything went wrong.
                log.warn("model returned an empty draft for incident {}, using template", context.incidentId());
                return fallback.draft(context);
            }
            return RcaDraft.fromModel(text.strip(), model);

        } catch (CompletionException | CancellationException e) {
            // Everything the guarded call can fail with arrives here: a timeout as
            // CancellationException, an open circuit and every provider error wrapped in
            // CompletionException. None of them should cost the incident its draft.
            Throwable cause = e instanceof CompletionException ? e.getCause() : e;
            log.warn(
                    "model unavailable for incident {} ({}), using deterministic summary",
                    context.incidentId(),
                    cause == null ? e.toString() : cause.toString());
            return fallback.draft(context);

        } catch (RuntimeException e) {
            log.warn("unexpected drafting failure for incident {}, using template", context.incidentId(), e);
            return fallback.draft(context);
        }
    }
}
