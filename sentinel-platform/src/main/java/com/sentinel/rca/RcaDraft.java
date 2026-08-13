package com.sentinel.rca;

/**
 * A drafted hypothesis and its provenance.
 *
 * @param text the draft itself, in the four sections the prompt contract fixes
 * @param model the model that wrote it, or {@link #TEMPLATE_MODEL} for the deterministic summary
 * @param fallback true when the model was unavailable and the template stood in for it
 */
public record RcaDraft(String text, String model, boolean fallback) {

    /** Recorded as the model name when no model was involved. */
    public static final String TEMPLATE_MODEL = "template";

    public static RcaDraft fromModel(String text, String model) {
        return new RcaDraft(text, model, false);
    }

    public static RcaDraft fromTemplate(String text) {
        return new RcaDraft(text, TEMPLATE_MODEL, true);
    }
}
