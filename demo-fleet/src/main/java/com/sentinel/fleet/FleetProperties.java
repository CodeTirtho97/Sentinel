package com.sentinel.fleet;

import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Everything that distinguishes one fleet instance from another. */
@ConfigurationProperties(prefix = "fleet")
public class FleetProperties {

    /** Value of the {@code service} metric tag, e.g. {@code checkout-service}. */
    private String serviceName = "demo-service";

    /** Base URLs this instance calls synchronously on every request. */
    private List<String> downstream = new ArrayList<>();

    /** Floor of the simulated work time, in milliseconds. */
    private long baseLatencyMs = 10;

    /** Upper bound of the random jitter added to {@link #baseLatencyMs}. */
    private long jitterMs = 20;

    public String getServiceName() {
        return serviceName;
    }

    public void setServiceName(String serviceName) {
        this.serviceName = serviceName;
    }

    public List<String> getDownstream() {
        return downstream;
    }

    public void setDownstream(List<String> downstream) {
        this.downstream = downstream;
    }

    public long getBaseLatencyMs() {
        return baseLatencyMs;
    }

    public void setBaseLatencyMs(long baseLatencyMs) {
        this.baseLatencyMs = baseLatencyMs;
    }

    public long getJitterMs() {
        return jitterMs;
    }

    public void setJitterMs(long jitterMs) {
        this.jitterMs = jitterMs;
    }
}
