package com.sentinel.slo;

import com.sentinel.config.SentinelProperties;
import com.sentinel.slo.api.SloRequests;
import com.sentinel.slo.domain.SloDefinition;
import com.sentinel.slo.domain.SloType;
import com.sentinel.slo.math.BurnRateCalculator;
import com.sentinel.slo.math.BurnRateResult;
import com.sentinel.slo.math.ErrorBudgetCalculator;
import com.sentinel.slo.math.WindowSample;
import com.sentinel.slo.metrics.MetricsSource;
import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SloDefinitionService {

    private static final Duration DEFAULT_ROLLING_WINDOW = Duration.ofDays(30);

    private final SloDefinitionRepository repository;
    private final SentinelProperties props;
    private final MetricsSource metrics;
    private final BurnRateCalculator calculator;
    private final TreeSet<Duration> requiredWindows;
    private final Clock clock;

    SloDefinitionService(
            SloDefinitionRepository repository,
            SentinelProperties props,
            MetricsSource metrics,
            BurnRateCalculator calculator,
            TreeSet<Duration> requiredWindows,
            Clock clock) {
        this.repository = repository;
        this.props = props;
        this.metrics = metrics;
        this.calculator = calculator;
        this.requiredWindows = requiredWindows;
        this.clock = clock;
    }

    @Transactional
    public SloDefinition create(SloRequests.Create request) {
        Duration rollingWindow = request.rollingWindow() == null ? DEFAULT_ROLLING_WINDOW : request.rollingWindow();
        validate(
                request.serviceName(),
                request.type(),
                request.objective(),
                request.latencyThresholdMs(),
                rollingWindow);

        var entity = new SloDefinitionEntity(
                UUID.randomUUID(),
                request.serviceName().trim(),
                request.type(),
                request.objective(),
                request.latencyThresholdMs(),
                rollingWindow,
                true,
                clock.instant());
        return repository.save(entity).toDomain();
    }

    @Transactional(readOnly = true)
    public List<SloDefinition> list() {
        return repository.findAll().stream().map(SloDefinitionEntity::toDomain).toList();
    }

    @Transactional(readOnly = true)
    public SloDefinition get(UUID id) {
        return entity(id).toDomain();
    }

    @Transactional
    public SloDefinition patch(UUID id, SloRequests.Patch request) {
        var entity = entity(id);
        if (request.objective() != null) {
            requireValidObjective(request.objective());
            entity.setObjective(request.objective());
        }
        if (request.enabled() != null) {
            entity.setEnabled(request.enabled());
        }
        entity.setUpdatedAt(clock.instant());
        return repository.save(entity).toDomain();
    }

    @Transactional
    public void delete(UUID id) {
        if (!repository.existsById(id)) {
            throw new SloNotFoundException(id);
        }
        repository.deleteById(id);
    }

    @Transactional(readOnly = true)
    public SloRequests.Budget budget(UUID id) {
        SloDefinition slo = entity(id).toDomain();

        Double remaining = metrics.budgetCounts(slo.serviceName(), slo.rollingWindow())
                .map(c -> ErrorBudgetCalculator.remaining(c.errors(), c.total(), slo.objective()))
                .orElse(null);

        BurnRateResult result = evaluateNow(slo);
        Double longBurn = null;
        Double shortBurn = null;
        String status;
        switch (result) {
            case BurnRateResult.Ok ok -> {
                longBurn = ok.longBurn();
                shortBurn = ok.shortBurn();
                status = "OK";
            }
            case BurnRateResult.Breach breach -> {
                longBurn = breach.longBurn();
                shortBurn = breach.shortBurn();
                status = "BREACH_" + breach.severity();
            }
            case BurnRateResult.InsufficientData insufficient -> status = "INSUFFICIENT_DATA";
        }

        return new SloRequests.Budget(
                slo.id(),
                slo.serviceName(),
                slo.type(),
                slo.objective(),
                slo.rollingWindow().toString(),
                remaining,
                longBurn,
                shortBurn,
                status);
    }

    private BurnRateResult evaluateNow(SloDefinition slo) {
        Map<Duration, WindowSample> samples = new HashMap<>();
        for (Duration window : requiredWindows) {
            metrics.errorRatio(slo.serviceName(), slo.type(), slo.latencyThresholdMs(), window)
                    .ifPresent(
                            er -> samples.put(window, new WindowSample(er.ratio(), er.totalEvents(), er.coverage())));
        }
        return calculator.evaluate(slo.objective(), samples);
    }

    private SloDefinitionEntity entity(UUID id) {
        return repository.findById(id).orElseThrow(() -> new SloNotFoundException(id));
    }

    private void validate(
            String serviceName, SloType type, double objective, Integer latencyThresholdMs, Duration rollingWindow) {
        if (serviceName == null || serviceName.isBlank()) {
            throw new SloValidationException("serviceName is required");
        }
        requireValidObjective(objective);
        if (rollingWindow.isZero() || rollingWindow.isNegative()) {
            throw new SloValidationException("rollingWindow must be a positive duration");
        }

        List<Integer> allowed = props.getSlo().getAllowedLatencyThresholdsMs();
        switch (type) {
            case AVAILABILITY -> {
                if (latencyThresholdMs != null) {
                    throw new SloValidationException("latencyThresholdMs must be null for an AVAILABILITY SLO");
                }
            }
            case LATENCY -> {
                if (latencyThresholdMs == null) {
                    throw new SloValidationException("latencyThresholdMs is required for a LATENCY SLO");
                }
                // A threshold without a matching histogram bucket cannot be evaluated at all.
                if (!allowed.contains(latencyThresholdMs)) {
                    throw new SloValidationException("latencyThresholdMs must match a configured histogram bucket "
                            + allowed + ", got " + latencyThresholdMs);
                }
            }
        }
    }

    private static void requireValidObjective(double objective) {
        if (!Double.isFinite(objective) || objective <= 0.0 || objective >= 1.0) {
            throw new SloValidationException(
                    "objective must be between 0 and 1 exclusive; 1.0 leaves a zero error budget, got " + objective);
        }
    }
}
