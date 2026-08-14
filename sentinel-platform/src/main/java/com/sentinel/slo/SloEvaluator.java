package com.sentinel.slo;

import com.sentinel.config.SentinelProperties;
import com.sentinel.correlation.CorrelationStore;
import com.sentinel.events.EventPublisher;
import com.sentinel.events.SloBreachEvent;
import com.sentinel.events.Topics;
import com.sentinel.slo.domain.Severity;
import com.sentinel.slo.domain.SloDefinition;
import com.sentinel.slo.domain.SloType;
import com.sentinel.slo.math.BurnRateCalculator;
import com.sentinel.slo.math.BurnRateResult;
import com.sentinel.slo.math.WindowSample;
import com.sentinel.slo.metrics.ErrorRatio;
import com.sentinel.slo.metrics.MetricsSource;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class SloEvaluator {

    private static final Logger log = LoggerFactory.getLogger(SloEvaluator.class);

    private final SloDefinitionRepository repository;
    private final MetricsSource metrics;
    private final BurnRateCalculator calculator;
    private final ShardAssignment shards;
    private final TreeSet<Duration> requiredWindows;
    private final MeterRegistry registry;
    private final EventPublisher publisher;
    private final CorrelationStore correlationStore;
    private final Clock clock;
    private final Duration evaluationInterval;
    private final Duration republishInterval;

    /**
     * What has already been announced, per SLO, so an ongoing breach is not re-announced every cycle.
     *
     * <p>In-memory and deliberately not persisted: after a restart the map is empty and every live
     * breach is re-announced once, which is the correct thing to do — a fresh process has told
     * nobody anything yet.
     */
    private final Map<UUID, Announced> announced = new ConcurrentHashMap<>();

    private record Announced(Severity severity, Instant at) {}

    SloEvaluator(
            SloDefinitionRepository repository,
            MetricsSource metrics,
            BurnRateCalculator calculator,
            ShardAssignment shards,
            TreeSet<Duration> requiredWindows,
            MeterRegistry registry,
            EventPublisher publisher,
            CorrelationStore correlationStore,
            Clock clock,
            SentinelProperties props) {
        this.repository = repository;
        this.metrics = metrics;
        this.calculator = calculator;
        this.shards = shards;
        this.requiredWindows = requiredWindows;
        this.registry = registry;
        this.publisher = publisher;
        this.correlationStore = correlationStore;
        this.clock = clock;
        this.evaluationInterval = props.getEvaluation().getInterval();
        this.republishInterval = props.getEvaluation().getBreachRepublishInterval();

        // The heartbeat is what keeps lastBreachAt fresh on an incident that is still burning. Let
        // it exceed either of these and a long outage goes quiet from the consumer's point of view,
        // its incident ages past the threshold, and the auto-resolver closes a live incident — the
        // same silent all-clear, arrived at from the other direction.
        Duration window = props.getCorrelation().getWindow();
        Duration resolveAfter = props.getCorrelation().getAutoResolveAfter();
        if (republishInterval.compareTo(window) >= 0 || republishInterval.compareTo(resolveAfter) >= 0) {
            throw new IllegalStateException("breach-republish-interval (%s) must be shorter than both the correlation window (%s) and auto-resolve-after (%s)"
                    .formatted(republishInterval, window, resolveAfter));
        }
    }

    /** One query per distinct (type, threshold, window) rather than one per service. */
    private record QueryKey(SloType type, Integer latencyThresholdMs, Duration window) {}

    @Scheduled(fixedDelayString = "${sentinel.evaluation.interval:15s}")
    @Transactional(readOnly = true)
    public void evaluateCycle() {
        Timer.Sample cycle = Timer.start(registry);
        try {
            List<SloDefinition> owned = repository.findByEnabledTrue().stream()
                    .map(SloDefinitionEntity::toDomain)
                    .filter(slo -> shards.owns(slo.serviceName()))
                    .toList();

            if (owned.isEmpty()) {
                log.debug("no enabled SLOs owned by shard {}/{}", shards.shardIndex(), shards.shardCount());
                return;
            }

            Map<QueryKey, Map<String, ErrorRatio>> fetched = fetchAll(owned);

            // One timestamp for the whole cycle, read once.
            //
            // Stamping each breach as it is produced looks harmless and quietly destroys origin
            // inference. A cascade through synchronous calls breaks every service in the chain at
            // the same instant, so all four cross the threshold in the same cycle — and the only
            // thing separating their timestamps is the order this loop happens to visit them in.
            // "Earliest breach wins" then resolves to "first in iteration order", which named
            // checkout-service as the origin of a failure that started in ledger-service.
            //
            // Sharing the cycle's instant makes those breaches genuinely tie, which is the truth:
            // they came from one query snapshot. The tie is then broken on depth in the call graph,
            // which is the signal that actually distinguishes a dependency from its callers.
            Instant detectedAt = clock.instant();

            List<SloBreachEvent> breaches = new ArrayList<>();
            for (SloDefinition slo : owned) {
                evaluateOne(slo, fetched, detectedAt).ifPresent(breaches::add);
            }

            // Two passes, and the separation is the point.
            //
            // Recording and publishing in one interleaved pass leaves a window in which the consumer
            // has already picked up the first breach while later ones are still being recorded. It
            // then correlates against a partial cycle, sees a component of one or two, and opens an
            // incident keyed on the wrong service — a second incident alongside the correct one, for
            // the same ongoing failure.
            //
            // Recording the whole cycle before publishing any of it closes that window: by the time
            // the first event is consumable, every breach it needs to correlate against is visible.
            // Announce state changes, not the passage of time.
            //
            // Re-publishing every breach on every cycle turns one ongoing outage into an unbounded
            // event stream: at 4,000 breaching SLOs on a 15s cycle that is 16,000 events a minute,
            // none of which tells any consumer something it does not already know. Measured, it
            // buried the breach consumer — 18,310 messages behind — and each event carried a fresh
            // deterministic id, so dedupe could not suppress any of it either.
            //
            // Alertmanager solves the same problem with repeat_interval, defaulted to four hours.
            // A cycle-rate re-announcement is 960x that. What downstream actually needs is: tell me
            // when something starts, when its severity moves, and often enough afterwards that I
            // can tell the difference between "still burning" and "you have stopped talking to me".
            List<SloBreachEvent> toAnnounce = breaches.stream()
                    .filter(breach -> shouldAnnounce(breach, detectedAt))
                    .toList();

            // Anything that recovered forgets its history, so a fresh breach announces immediately
            // rather than waiting out a heartbeat left over from the previous episode.
            Set<UUID> stillBreaching =
                    breaches.stream().map(SloBreachEvent::sloId).collect(Collectors.toSet());
            announced.keySet().retainAll(stillBreaching);

            int suppressed = breaches.size() - toAnnounce.size();
            if (suppressed > 0) {
                registry.counter("sentinel.breaches.suppressed").increment(suppressed);
            }

            toAnnounce.forEach(correlationStore::record);
            toAnnounce.forEach(event -> publisher.publish(Topics.SLO_BREACH, event.serviceName(), event));
        } catch (RuntimeException e) {
            log.error("evaluation cycle failed", e);
            registry.counter("sentinel.slo.cycle.failures").increment();
        } finally {
            cycle.stop(registry.timer("sentinel.slo.cycle.duration"));
        }
    }

    /**
     * True when this breach is news: newly broken, changed severity, or the heartbeat is due.
     *
     * <p>Records the decision as a side effect so the next cycle can compare against it.
     */
    private boolean shouldAnnounce(SloBreachEvent breach, Instant detectedAt) {
        Announced previous = announced.get(breach.sloId());
        boolean news = previous == null
                || previous.severity() != breach.severity()
                || !detectedAt.isBefore(previous.at().plus(republishInterval));
        if (news) {
            announced.put(breach.sloId(), new Announced(breach.severity(), detectedAt));
        }
        return news;
    }

    private Map<QueryKey, Map<String, ErrorRatio>> fetchAll(List<SloDefinition> slos) {
        Set<QueryKey> keys = new LinkedHashSet<>();
        for (SloDefinition slo : slos) {
            for (Duration window : requiredWindows) {
                keys.add(new QueryKey(slo.type(), slo.latencyThresholdMs(), window));
            }
        }

        Map<QueryKey, Map<String, ErrorRatio>> results = new HashMap<>(keys.size());
        // Virtual threads: the fan-out is blocking HTTP, so platform threads would be wasted here.
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Map<QueryKey, Future<Map<String, ErrorRatio>>> pending = new HashMap<>(keys.size());
            for (QueryKey key : keys) {
                pending.put(
                        key,
                        executor.submit(() -> metrics.errorRatios(key.type(), key.latencyThresholdMs(), key.window())));
            }
            pending.forEach((key, future) -> {
                try {
                    results.put(key, future.get());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    results.put(key, Map.of());
                } catch (Exception e) {
                    log.warn("query {} failed: {}", key, e.toString());
                    results.put(key, Map.of());
                }
            });
        }
        return results;
    }

    /** @return the breach this SLO produced, or empty when it is healthy or unjudgeable */
    private Optional<SloBreachEvent> evaluateOne(
            SloDefinition slo, Map<QueryKey, Map<String, ErrorRatio>> fetched, Instant detectedAt) {
        Map<Duration, WindowSample> samples = new HashMap<>();
        for (Duration window : requiredWindows) {
            Map<String, ErrorRatio> byService =
                    fetched.getOrDefault(new QueryKey(slo.type(), slo.latencyThresholdMs(), window), Map.of());
            ErrorRatio observed = byService.get(slo.serviceName());
            if (observed != null) {
                samples.put(window, new WindowSample(observed.ratio(), observed.totalEvents(), observed.coverage()));
            }
        }

        Timer.Sample evaluation = Timer.start(registry);
        BurnRateResult result = calculator.evaluate(slo.objective(), samples);
        evaluation.stop(registry.timer(
                "sentinel.slo.evaluation.duration", "slo_type", slo.type().name()));

        switch (result) {
            case BurnRateResult.Breach breach -> {
                registry.counter("sentinel.slo.evaluations.total", "result", "breach")
                        .increment();
                log.warn(
                        "BREACH {} {} {} longBurn={} shortBurn={} objective={}",
                        breach.severity(),
                        slo.serviceName(),
                        slo.type(),
                        String.format("%.2f", breach.longBurn()),
                        String.format("%.2f", breach.shortBurn()),
                        slo.objective());
                return Optional.of(toEvent(slo, breach, detectedAt));
            }
            case BurnRateResult.Ok ok -> {
                registry.counter("sentinel.slo.evaluations.total", "result", "ok")
                        .increment();
                log.debug(
                        "ok {} {} longBurn={} shortBurn={}",
                        slo.serviceName(),
                        slo.type(),
                        String.format("%.2f", ok.longBurn()),
                        String.format("%.2f", ok.shortBurn()));
            }
            case BurnRateResult.InsufficientData insufficient -> {
                registry.counter("sentinel.slo.evaluations.total", "result", "insufficient")
                        .increment();
                log.debug("insufficient data {} {}: {}", slo.serviceName(), slo.type(), insufficient.reason());
            }
        }
        return Optional.empty();
    }

    private SloBreachEvent toEvent(SloDefinition slo, BurnRateResult.Breach breach, Instant detectedAt) {
        return SloBreachEvent.of(
                slo.id(),
                slo.serviceName(),
                slo.type(),
                breach.severity(),
                breach.longBurn(),
                breach.shortBurn(),
                detectedAt,
                evaluationInterval);
    }
}
