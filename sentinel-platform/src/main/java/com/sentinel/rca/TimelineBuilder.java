package com.sentinel.rca;

import com.sentinel.correlation.DependencyGraph;
import com.sentinel.incident.Incident;
import com.sentinel.incident.IncidentEventLog;
import java.time.Duration;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.stereotype.Component;

/**
 * Assembles the incident's evidence, and renders it two ways.
 *
 * <p>{@link #render} produces the model's prompt. {@link #plainTextSummary} produces the
 * deterministic fallback — the same four sections, written from the same data, with no model
 * involved. Keeping both here is deliberate: the fallback is not a stub, it is the floor the
 * feature degrades to, and it stays truthful for free because it can only restate what it is given.
 */
@Component
public class TimelineBuilder {

    /** Wall-clock time of day, which is how an on-call engineer reads a timeline. */
    private static final DateTimeFormatter CLOCK =
            DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneOffset.UTC);

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ISO_INSTANT;

    private final DependencyGraph graph;

    TimelineBuilder(DependencyGraph graph) {
        this.graph = graph;
    }

    /**
     * Builds the context from an incident and its timeline.
     *
     * <p>The edge list is induced by the affected set rather than being the whole topology. At eight
     * services the difference is cosmetic; at a thousand it is the difference between a prompt and a
     * phone book, and either way the model should not be shown services that had nothing to do with
     * this incident.
     */
    public IncidentContext build(Incident incident, List<IncidentEventLog> timeline) {
        Set<String> affected = new TreeSet<>(incident.getAffectedServices());

        List<IncidentContext.Edge> edges = new ArrayList<>();
        for (Map.Entry<String, Set<String>> entry : graph.edges().entrySet()) {
            if (!affected.contains(entry.getKey())) {
                continue;
            }
            for (String callee : entry.getValue()) {
                if (affected.contains(callee)) {
                    edges.add(new IncidentContext.Edge(entry.getKey(), callee));
                }
            }
        }

        List<IncidentContext.BreachEntry> breaches = timeline.stream()
                .filter(e -> e.getKind() == IncidentEventLog.Kind.BREACH)
                .sorted(Comparator.comparing(IncidentEventLog::getOccurredAt)
                        // Same-second breaches are the normal case in a cascade, so the tie-break
                        // has to be stable or the prompt — and every assertion over it — churns.
                        .thenComparing(IncidentEventLog::getServiceName))
                .map(e -> new IncidentContext.BreachEntry(
                        e.getOccurredAt(),
                        e.getServiceName(),
                        e.getSloType(),
                        e.getSeverity(),
                        e.getLongBurnRate() == null ? 0.0 : e.getLongBurnRate(),
                        e.getShortBurnRate() == null ? 0.0 : e.getShortBurnRate()))
                .toList();

        return new IncidentContext(
                incident.getId(),
                incident.getOpenedAt(),
                incident.getSeverity(),
                incident.getOriginService(),
                affected,
                List.copyOf(edges),
                breaches);
    }

    /** The user message: the incident as compact structured facts, and nothing else. */
    public static String render(IncidentContext ctx) {
        var out = new StringBuilder(512);

        out.append("INCIDENT:\n")
                .append("  opened_at: ")
                .append(STAMP.format(ctx.openedAt()))
                .append('\n')
                .append("  severity: ")
                .append(ctx.severity())
                .append('\n')
                .append("  affected: ")
                .append(String.join(", ", ctx.affectedServices()))
                .append('\n')
                .append("  inferred_origin: ")
                .append(ctx.originService() == null ? "unknown" : ctx.originService())
                .append('\n');

        out.append("\nDEPENDENCY EDGES:\n");
        if (ctx.dependencyEdges().isEmpty()) {
            out.append("  (none between the affected services)\n");
        } else {
            for (IncidentContext.Edge edge : ctx.dependencyEdges()) {
                out.append("  ")
                        .append(edge.from())
                        .append(" -> ")
                        .append(edge.to())
                        .append('\n');
            }
        }

        out.append("\nBREACH TIMELINE:\n");
        if (ctx.timeline().isEmpty()) {
            out.append("  (no breaches recorded)\n");
        } else {
            for (IncidentContext.BreachEntry breach : ctx.timeline()) {
                out.append(String.format(
                        "  %s  %-18s %-13s burn=%.1f%n",
                        CLOCK.format(breach.detectedAt()),
                        breach.serviceName(),
                        breach.sloType(),
                        breach.longBurnRate()));
            }
        }

        return out.toString();
    }

    /**
     * The deterministic fallback draft, used whenever the model is unavailable or not configured.
     *
     * <p>Same four sections as the prompt demands of the model, so a reader gets the same shape of
     * answer either way. It states what happened and what to look at; it does not speculate, which
     * is the one thing a summary written without a model genuinely cannot do.
     */
    public static String plainTextSummary(IncidentContext ctx) {
        var out = new StringBuilder(512);
        String origin = ctx.originService() == null ? "could not be inferred" : ctx.originService();
        int count = ctx.affectedServices().size();

        out.append("SUMMARY\n");
        out.append(String.format(
                "%s severity incident opened at %s affecting %d service%s: %s. "
                        + "%d breach%s recorded on the timeline.%n",
                ctx.severity(),
                STAMP.format(ctx.openedAt()),
                count,
                count == 1 ? "" : "s",
                String.join(", ", ctx.affectedServices()),
                ctx.timeline().size(),
                ctx.timeline().size() == 1 ? "" : "es"));

        out.append("\nLIKELY ORIGIN\n");
        if (ctx.timeline().isEmpty()) {
            out.append(String.format("%s, by correlation key. No breach timeline to corroborate it.%n", origin));
        } else {
            IncidentContext.BreachEntry first = ctx.timeline().getFirst();
            out.append(String.format(
                    "%s. It breached first, at %s (%s, burn rate %.1f).%n",
                    origin, STAMP.format(first.detectedAt()), first.sloType(), first.longBurnRate()));
            Duration spread = Duration.between(
                    first.detectedAt(), ctx.timeline().getLast().detectedAt());
            if (!spread.isZero() && !spread.isNegative()) {
                out.append(String.format("The remaining breaches followed within %ds.%n", spread.toSeconds()));
            }
        }

        out.append("\nBLAST RADIUS\n");
        out.append(String.join(", ", ctx.affectedServices())).append('\n');
        if (!ctx.dependencyEdges().isEmpty()) {
            out.append("Connected by: ");
            out.append(ctx.dependencyEdges().stream()
                    .map(e -> e.from() + " -> " + e.to())
                    .reduce((a, b) -> a + "; " + b)
                    .orElse(""));
            out.append('\n');
        }

        out.append("\nWHAT TO CHECK NEXT\n");
        out.append(String.format("1. Recent deploys and config changes on %s.%n", origin));
        out.append(String.format("2. Saturation and dependency health for %s.%n", origin));
        out.append("3. Whether the downstream breaches recover once the origin does.\n");
        out.append(
                "\n(Generated without a language model. This is the recorded timeline, " + "not a causal analysis.)\n");

        return out.toString();
    }
}
