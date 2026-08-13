-- Root cause analysis, drafted asynchronously off incident.opened.v1.
--
-- Nullable by design: an incident is created and useful before any draft exists, and the RCA
-- consumer fills these in afterwards. A NOT NULL here would couple incident creation to the
-- availability of a language model, which is exactly the coupling the async design avoids.
ALTER TABLE incident
    ADD COLUMN rca_draft        TEXT,
    -- Which model produced the draft, or 'template' for the deterministic fallback. Recorded so a
    -- draft can be read with the knowledge of what wrote it — a fallback summary and a model
    -- narrative deserve different amounts of trust.
    ADD COLUMN rca_model        VARCHAR(128),
    ADD COLUMN rca_generated_at TIMESTAMPTZ,
    -- True when the deterministic timeline summary was used because the model was unavailable.
    -- Surfaced on the API so a reader is never misled about where the text came from.
    ADD COLUMN rca_fallback     BOOLEAN,

    -- Either the whole draft is present or none of it is. A row with text but no timestamp means
    -- some write path forgot half the story, and that is worth failing on rather than serving.
    ADD CONSTRAINT incident_rca_complete CHECK (
        (rca_draft IS NULL AND rca_model IS NULL AND rca_generated_at IS NULL AND rca_fallback IS NULL)
        OR
        (rca_draft IS NOT NULL AND rca_model IS NOT NULL AND rca_generated_at IS NOT NULL AND rca_fallback IS NOT NULL)
    );

-- GET /incidents/{id}/rca answers 202 while a draft is pending, so "which incidents still have no
-- draft" is a query the API makes on every poll during a live incident.
CREATE INDEX idx_incident_awaiting_rca
    ON incident (opened_at DESC)
    WHERE rca_draft IS NULL;
