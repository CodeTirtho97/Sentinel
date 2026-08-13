package com.sentinel.rca;

/**
 * The floor the feature degrades to: the recorded timeline, restated in the four sections.
 *
 * <p>Not a stub. This is what {@code make demo} produces with no API key, and what every incident
 * gets when the provider is down, so it has to read like a deliverable rather than an apology. It
 * also cannot hallucinate, because it can only restate what it was handed — which is why the model
 * path falls back to it rather than to an error string.
 */
public class TemplateRcaDrafter implements RcaDrafter {

    @Override
    public RcaDraft draft(IncidentContext context) {
        return RcaDraft.fromTemplate(TimelineBuilder.plainTextSummary(context));
    }
}
