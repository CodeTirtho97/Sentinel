package com.sentinel.rca;

/**
 * Seam: how an incident hypothesis gets written.
 *
 * <p>Two implementations ship. {@link TemplateRcaDrafter} is deterministic and always available;
 * {@link SpringAiRcaDrafter} calls a model and degrades to the template one when it cannot. The
 * seam exists because the LLM must be optional — a reviewer with no API key still gets a useful
 * incident, and a provider outage must not become an outage of the observability platform.
 *
 * <p>Deliberately synchronous. The asynchrony that matters lives at the Kafka boundary, where a 10s
 * model call is already off the 15s evaluation path; adding a second async layer inside the drafter
 * would buy nothing and make the resilience annotations harder to reason about.
 */
public interface RcaDrafter {

    /**
     * Drafts a hypothesis. Must not throw: a drafter that cannot reach its model returns the
     * deterministic summary instead, because an incident with a plain timeline still does its job.
     */
    RcaDraft draft(IncidentContext context);
}
