package com.sentinel.synth;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Every knob the load test turns, all of them environment variables. */
@ConfigurationProperties(prefix = "synthetic")
public class SyntheticProperties {

    /** How many fake services to expose. The headline load-test variable. */
    private int services = 100;

    /**
     * Services per dependency chain.
     *
     * <p>Chains are what make correlation do real work here. A flat list of unrelated services
     * would produce one incident per breach and an alert-collapse ratio of exactly 1:1, measuring
     * nothing. Five is the depth of the real demo fleet's order path.
     */
    private int chainLength = 5;

    /** Requests per second per service. Drives how fast counters advance between scrapes. */
    private double rps = 20.0;

    /** Baseline error rate. Well inside a 0.999 objective, so a healthy service stays healthy. */
    private double errorRate = 0.0005;

    /** Fraction of <b>chains</b> in breach. Whole chains break, because that is what a cascade is. */
    private double breachFraction = 0.0;

    /** Error rate for a service in breach. 0.30 against a 0.001 budget is a burn rate of 300. */
    private double breachErrorRate = 0.30;

    public int getServices() {
        return services;
    }

    public void setServices(int services) {
        this.services = services;
    }

    public int getChainLength() {
        return chainLength;
    }

    public void setChainLength(int chainLength) {
        this.chainLength = chainLength;
    }

    public double getRps() {
        return rps;
    }

    public void setRps(double rps) {
        this.rps = rps;
    }

    public double getErrorRate() {
        return errorRate;
    }

    public void setErrorRate(double errorRate) {
        this.errorRate = errorRate;
    }

    public double getBreachFraction() {
        return breachFraction;
    }

    public void setBreachFraction(double breachFraction) {
        this.breachFraction = breachFraction;
    }

    public double getBreachErrorRate() {
        return breachErrorRate;
    }

    public void setBreachErrorRate(double breachErrorRate) {
        this.breachErrorRate = breachErrorRate;
    }
}
