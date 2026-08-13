package com.sentinel.fleet;

import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Failure injection endpoints, driven by the demo scripts. */
@RestController
@RequestMapping("/chaos")
public class ChaosController {

    private static final Logger log = LoggerFactory.getLogger(ChaosController.class);

    private final ChaosState state;
    private final FleetProperties props;

    ChaosController(ChaosState state, FleetProperties props) {
        this.state = state;
        this.props = props;
    }

    @GetMapping
    public Map<String, Object> current() {
        return Map.of("service", props.getServiceName(), "chaos", state.snapshot());
    }

    @PostMapping("/errors")
    public Map<String, Object> errors(@RequestParam double rate) {
        state.setErrorRate(rate);
        log.warn("CHAOS {} error rate -> {}", props.getServiceName(), state.getErrorRate());
        return current();
    }

    @PostMapping("/latency")
    public Map<String, Object> latency(@RequestParam long ms) {
        state.setLatencyMs(ms);
        log.warn("CHAOS {} added latency -> {}ms", props.getServiceName(), state.getLatencyMs());
        return current();
    }

    @PostMapping("/hang")
    public Map<String, Object> hang(@RequestParam(defaultValue = "true") boolean enabled) {
        state.setHang(enabled);
        log.warn("CHAOS {} hang -> {}", props.getServiceName(), enabled);
        return current();
    }

    @PostMapping("/reset")
    public Map<String, Object> reset() {
        state.reset();
        log.warn("CHAOS {} reset", props.getServiceName());
        return current();
    }
}
