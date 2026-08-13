-- The static dependency graph. Configured, not discovered — seeded from application.yml at startup.
CREATE TABLE service_dependency (
    service_name VARCHAR(255) NOT NULL,
    depends_on   VARCHAR(255) NOT NULL,

    PRIMARY KEY (service_name, depends_on),

    -- A self-edge would make every component walk trivially reflexive and hide real topology bugs.
    CONSTRAINT service_dependency_not_self CHECK (service_name <> depends_on)
);

CREATE INDEX idx_service_dependency_depends_on ON service_dependency (depends_on);


CREATE TABLE incident (
    id               UUID         PRIMARY KEY,

    -- Frozen at creation: the origin service. Never a hash of the member set, which grows as a
    -- cascade propagates and would split one incident into one-per-breach.
    correlation_key  VARCHAR(255) NOT NULL,

    state            VARCHAR(32)  NOT NULL,
    severity         VARCHAR(32)  NOT NULL,

    -- Recomputed for display as the timeline fills in; the correlation_key does not follow it.
    origin_service   VARCHAR(255),

    opened_at        TIMESTAMPTZ  NOT NULL,
    acknowledged_at  TIMESTAMPTZ,
    mitigated_at     TIMESTAMPTZ,
    resolved_at      TIMESTAMPTZ,

    -- Drives auto-resolution: no member breach since this instant for auto-resolve-after.
    last_breach_at   TIMESTAMPTZ  NOT NULL,

    breach_count     INTEGER      NOT NULL DEFAULT 0,
    version          BIGINT       NOT NULL DEFAULT 0,

    CONSTRAINT incident_state_known CHECK (state IN ('OPEN', 'ACKNOWLEDGED', 'MITIGATED', 'RESOLVED')),
    CONSTRAINT incident_severity_known CHECK (severity IN ('MEDIUM', 'HIGH', 'CRITICAL')),
    CONSTRAINT incident_resolved_has_timestamp CHECK (state <> 'RESOLVED' OR resolved_at IS NOT NULL)
);

-- The last line of defence against duplicate incidents. Two concurrent breaches for one key race
-- on this index and exactly one wins; the loser's INSERT ... ON CONFLICT DO NOTHING is a no-op and
-- it re-reads the winner's row.
--
-- Being partial is the point: once an incident resolves, the same service may legitimately break
-- again and must be able to open a new incident under the same key.
CREATE UNIQUE INDEX idx_active_incident
    ON incident (correlation_key)
    WHERE state <> 'RESOLVED';

CREATE INDEX idx_incident_state_opened ON incident (state, opened_at DESC);
CREATE INDEX idx_incident_opened_at ON incident (opened_at DESC);
-- Supports the auto-resolve sweep, which only ever looks at unresolved incidents.
CREATE INDEX idx_incident_unresolved_last_breach
    ON incident (last_breach_at)
    WHERE state <> 'RESOLVED';


CREATE TABLE incident_affected_service (
    incident_id  UUID         NOT NULL REFERENCES incident (id) ON DELETE CASCADE,
    service_name VARCHAR(255) NOT NULL,

    PRIMARY KEY (incident_id, service_name)
);


CREATE TABLE incident_event_log (
    id             UUID         PRIMARY KEY,
    incident_id    UUID         NOT NULL REFERENCES incident (id) ON DELETE CASCADE,

    kind           VARCHAR(32)  NOT NULL,

    -- The deterministic breach event id. NULL for lifecycle entries, which have no source event.
    event_id       UUID,

    service_name   VARCHAR(255),
    slo_type       VARCHAR(32),
    severity       VARCHAR(32),
    long_burn_rate DOUBLE PRECISION,
    short_burn_rate DOUBLE PRECISION,
    message        TEXT,
    occurred_at    TIMESTAMPTZ  NOT NULL,

    CONSTRAINT incident_event_kind_known CHECK (kind IN ('BREACH', 'STATE_CHANGE', 'RCA'))
);

-- A redelivered breach must widen the incident without duplicating its timeline entry. This is the
-- companion to the Redis dedupe key: Redis skips the common case cheaply, this closes the window
-- between the database commit and the key being set.
--
-- Scoped to event_id alone rather than (incident_id, event_id): one breach detection belongs to
-- exactly one incident, so the same event surfacing under a second incident is a bug to block, not
-- a duplicate to permit.
CREATE UNIQUE INDEX idx_event_log_unique_event
    ON incident_event_log (event_id)
    WHERE event_id IS NOT NULL;

CREATE INDEX idx_event_log_incident_time ON incident_event_log (incident_id, occurred_at);
