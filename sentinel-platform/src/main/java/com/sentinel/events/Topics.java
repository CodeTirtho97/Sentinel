package com.sentinel.events;

/** Topic names are versioned in the name so a schema change is a new topic, not a silent break. */
public final class Topics {

    /** Keyed by serviceName so all breaches for one service share a partition and stay ordered. */
    public static final String SLO_BREACH = "slo.breach.v1";

    /** Keyed by incidentId. Consumed by the RCA drafter in Phase 3. */
    public static final String INCIDENT_OPENED = "incident.opened.v1";

    /** Keyed by incidentId. Consumed by the audit log. */
    public static final String INCIDENT_STATE_CHANGED = "incident.state-changed.v1";

    /** Dead letter suffix. Matches the DeadLetterPublishingRecoverer destination resolver. */
    private static final String DLT_SUFFIX = ".DLT";

    private Topics() {}

    public static String dlt(String topic) {
        return topic + DLT_SUFFIX;
    }
}
