package com.sentinel.fleet;

import java.util.Map;
import org.springframework.stereotype.Component;

/** Failure injection knobs for one instance. Written rarely, read on every request. */
@Component
public class ChaosState {

    private volatile double errorRate = 0.0;
    private volatile long latencyMs = 0;
    private volatile boolean hang = false;

    public double getErrorRate() {
        return errorRate;
    }

    public void setErrorRate(double errorRate) {
        this.errorRate = Math.clamp(errorRate, 0.0, 1.0);
    }

    public long getLatencyMs() {
        return latencyMs;
    }

    public void setLatencyMs(long latencyMs) {
        this.latencyMs = Math.max(0, latencyMs);
    }

    public boolean isHang() {
        return hang;
    }

    public void setHang(boolean hang) {
        this.hang = hang;
    }

    public void reset() {
        this.errorRate = 0.0;
        this.latencyMs = 0;
        this.hang = false;
    }

    public Map<String, Object> snapshot() {
        return Map.of("errorRate", errorRate, "latencyMs", latencyMs, "hang", hang);
    }
}
