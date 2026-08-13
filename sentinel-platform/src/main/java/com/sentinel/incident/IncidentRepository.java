package com.sentinel.incident;

import com.sentinel.slo.domain.Severity;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface IncidentRepository extends JpaRepository<Incident, UUID> {

    /**
     * Creates the incident only if no active one already holds the key.
     *
     * <p>Written as native SQL because Hibernate does not generate {@code ON CONFLICT}. The
     * alternative — catching {@code DataIntegrityViolationException} and re-reading — also works,
     * but burns a rolled-back transaction on every duplicate, and during a storm duplicates are the
     * common case rather than the rare one.
     *
     * <p>The repeated {@code WHERE} on the conflict target is mandatory, not stylistic: Postgres
     * infers a <i>partial</i> unique index only when the statement restates its predicate. Omit it
     * and Postgres looks for a total unique index on {@code correlation_key}, fails to find one, and
     * errors out.
     *
     * @return 1 if this call created the incident, 0 if one already existed
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
            value =
                    """
                    INSERT INTO incident (
                        id, correlation_key, state, severity, origin_service,
                        opened_at, last_breach_at, breach_count, version
                    )
                    VALUES (
                        :id, :correlationKey, 'OPEN', :severity, :originService,
                        :openedAt, :lastBreachAt, 0, 0
                    )
                    ON CONFLICT (correlation_key) WHERE state <> 'RESOLVED' DO NOTHING
                    """,
            nativeQuery = true)
    int insertIfAbsent(
            @Param("id") UUID id,
            @Param("correlationKey") String correlationKey,
            @Param("severity") String severity,
            @Param("originService") String originService,
            @Param("openedAt") Instant openedAt,
            @Param("lastBreachAt") Instant lastBreachAt);

    /**
     * Takes a row lock on the active incident for this key.
     *
     * <p>Selects the id rather than the entity deliberately: {@code FOR UPDATE} against a query that
     * also fetches the affected-services collection is the kind of thing that turns into an outer
     * join Postgres refuses to lock. Locking the id, then loading by id in the same transaction, is
     * unambiguous.
     */
    @Query(
            value = "SELECT id FROM incident WHERE correlation_key = :key AND state <> 'RESOLVED' FOR UPDATE",
            nativeQuery = true)
    Optional<UUID> lockActiveIdByCorrelationKey(@Param("key") String key);

    /**
     * Writes the RCA draft without touching the rest of the row.
     *
     * <p>A targeted UPDATE rather than load-modify-save, for two reasons that both bite in
     * production.
     *
     * <p>First, <b>optimistic locking</b>. {@code Incident} carries a {@code @Version}, which exists
     * to stop concurrent breach attachments losing each other's affected-services set. Rewriting the
     * whole entity to store a draft drags the RCA write into that same contention: an operator
     * acknowledging an incident while the drafter is finishing produces a
     * {@code StaleObjectStateException} and a 500, even though the two touch entirely disjoint
     * columns and cannot meaningfully conflict.
     *
     * <p>Second, <b>draft-once becomes atomic</b>. The {@code rca_draft IS NULL} guard lives in the
     * statement, so two drafters racing on one incident result in one write and one no-op rather
     * than in the second silently overwriting the first. {@code rca:regenerate} passes
     * {@code force} to bypass it deliberately.
     *
     * @return 1 if this call wrote the draft, 0 if one already existed or the incident is gone
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
            value =
                    """
                    UPDATE incident
                       SET rca_draft = :draft,
                           rca_model = :model,
                           rca_fallback = :fallback,
                           rca_generated_at = :generatedAt
                     WHERE id = :id
                       AND (:force = TRUE OR rca_draft IS NULL)
                    """,
            nativeQuery = true)
    int updateRca(
            @Param("id") UUID id,
            @Param("draft") String draft,
            @Param("model") String model,
            @Param("fallback") boolean fallback,
            @Param("generatedAt") Instant generatedAt,
            @Param("force") boolean force);

    /** Candidates for auto-resolution: unresolved and quiet since the cutoff. */
    @Query("SELECT i FROM Incident i WHERE i.state <> com.sentinel.incident.IncidentState.RESOLVED "
            + "AND i.lastBreachAt < :cutoff")
    List<Incident> findStaleUnresolved(@Param("cutoff") Instant cutoff);

    @Query("SELECT i FROM Incident i WHERE (:state IS NULL OR i.state = :state) "
            + "AND (:severity IS NULL OR i.severity = :severity) "
            + "AND (CAST(:since AS timestamp) IS NULL OR i.openedAt >= :since) "
            + "ORDER BY i.openedAt DESC")
    Page<Incident> search(
            @Param("state") IncidentState state,
            @Param("severity") Severity severity,
            @Param("since") Instant since,
            Pageable pageable);

    @Query("SELECT i.severity, COUNT(i) FROM Incident i "
            + "WHERE i.state <> com.sentinel.incident.IncidentState.RESOLVED GROUP BY i.severity")
    List<Object[]> countActiveBySeverity();
}
