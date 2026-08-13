package com.sentinel.slo;

import com.sentinel.slo.domain.SloDefinition;
import com.sentinel.slo.domain.SloType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "slo_definition")
public class SloDefinitionEntity {

    @Id
    private UUID id;

    @Column(name = "service_name", nullable = false)
    private String serviceName;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 32)
    private SloType type;

    @Column(name = "objective", nullable = false)
    private double objective;

    @Column(name = "latency_threshold_ms")
    private Integer latencyThresholdMs;

    @Column(name = "rolling_window_seconds", nullable = false)
    private long rollingWindowSeconds;

    @Column(name = "enabled", nullable = false)
    private boolean enabled;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected SloDefinitionEntity() {}

    public SloDefinitionEntity(
            UUID id,
            String serviceName,
            SloType type,
            double objective,
            Integer latencyThresholdMs,
            Duration rollingWindow,
            boolean enabled,
            Instant now) {
        this.id = id;
        this.serviceName = serviceName;
        this.type = type;
        this.objective = objective;
        this.latencyThresholdMs = latencyThresholdMs;
        this.rollingWindowSeconds = rollingWindow.toSeconds();
        this.enabled = enabled;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public SloDefinition toDomain() {
        return new SloDefinition(
                id,
                serviceName,
                type,
                objective,
                latencyThresholdMs,
                Duration.ofSeconds(rollingWindowSeconds),
                enabled);
    }

    public UUID getId() {
        return id;
    }

    public String getServiceName() {
        return serviceName;
    }

    public SloType getType() {
        return type;
    }

    public double getObjective() {
        return objective;
    }

    public void setObjective(double objective) {
        this.objective = objective;
    }

    public Integer getLatencyThresholdMs() {
        return latencyThresholdMs;
    }

    public long getRollingWindowSeconds() {
        return rollingWindowSeconds;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
