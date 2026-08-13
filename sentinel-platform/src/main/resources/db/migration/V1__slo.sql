CREATE TABLE slo_definition (
    id                     UUID PRIMARY KEY,
    service_name           VARCHAR(255)     NOT NULL,
    type                   VARCHAR(32)      NOT NULL,
    objective              DOUBLE PRECISION NOT NULL,
    latency_threshold_ms   INTEGER,
    rolling_window_seconds BIGINT           NOT NULL,
    enabled                BOOLEAN          NOT NULL DEFAULT TRUE,
    created_at             TIMESTAMPTZ      NOT NULL,
    updated_at             TIMESTAMPTZ      NOT NULL,

    CONSTRAINT slo_type_known CHECK (type IN ('AVAILABILITY', 'LATENCY')),

    -- An objective of 1.0 leaves a zero error budget, which makes burn rate a division by zero.
    CONSTRAINT slo_objective_range CHECK (objective > 0 AND objective < 1),

    CONSTRAINT slo_rolling_window_positive CHECK (rolling_window_seconds > 0),

    -- A latency threshold is required for LATENCY and meaningless for AVAILABILITY.
    CONSTRAINT slo_latency_threshold_matches_type CHECK (
        (type = 'LATENCY' AND latency_threshold_ms IS NOT NULL AND latency_threshold_ms > 0)
        OR (type = 'AVAILABILITY' AND latency_threshold_ms IS NULL)
    )
);

-- One SLO per service per target. COALESCE keeps NULL thresholds from defeating the index.
CREATE UNIQUE INDEX idx_slo_unique_target
    ON slo_definition (service_name, type, COALESCE(latency_threshold_ms, -1));

CREATE INDEX idx_slo_enabled ON slo_definition (service_name) WHERE enabled;
