package com.sentinel.fleet;

import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** The endpoint the load generator hits. */
@RestController
public class WorkController {

    private static final long HANG_MS = 60_000;

    private final ChaosState chaos;
    private final DependencyCaller downstream;
    private final FleetProperties props;

    WorkController(ChaosState chaos, DependencyCaller downstream, FleetProperties props) {
        this.chaos = chaos;
        this.downstream = downstream;
        this.props = props;
    }

    @GetMapping("/work")
    public Map<String, Object> work() {
        if (chaos.isHang()) {
            sleep(HANG_MS);
        }

        sleep(props.getBaseLatencyMs() + ThreadLocalRandom.current().nextLong(props.getJitterMs() + 1));

        long injected = chaos.getLatencyMs();
        if (injected > 0) {
            sleep(injected);
        }

        double rate = chaos.getErrorRate();
        if (rate > 0 && ThreadLocalRandom.current().nextDouble() < rate) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "injected failure");
        }

        downstream.callAll();

        return Map.of("service", props.getServiceName(), "ok", true);
    }

    private static void sleep(long ms) {
        if (ms <= 0) {
            return;
        }
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "interrupted", e);
        }
    }
}
