package com.sentinel.demo;

import com.sentinel.config.SentinelProperties;
import com.sentinel.slo.SloDefinitionService;
import com.sentinel.slo.api.SloRequests;
import com.sentinel.slo.domain.SloType;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;

/**
 * Drives the demo from the visualiser instead of from a terminal.
 *
 * <p><b>{@code @Profile("demo")} is the whole safety story.</b> These endpoints deliberately inject
 * failure into other services, which is exactly what you never want reachable in a real deployment.
 * Outside the demo profile the bean is not created and the routes return 404 — not disabled by a
 * flag someone can flip, simply absent.
 */
@RestController
@RequestMapping("/api/v1/demo")
@Profile("demo")
public class DemoControlController {

    private static final Logger log = LoggerFactory.getLogger(DemoControlController.class);

    /** Above the 500ms latency threshold at the broken service itself, not merely once accumulated. */
    private static final int CHAOS_LATENCY_MS = 600;

    private static final double CHAOS_ERROR_RATE = 0.35;
    private static final Duration ROLLING_WINDOW = Duration.ofDays(30);
    private static final int LATENCY_THRESHOLD_MS = 500;

    private final SloDefinitionService slos;
    private final Map<String, String> fleet;
    private final RestClient http;

    DemoControlController(SloDefinitionService slos, SentinelProperties props, RestClient.Builder builder) {
        this.slos = slos;
        this.fleet = props.getDemo().getFleet();
        this.http = builder.build();
    }

    public record ServiceStatus(String name, String url, boolean up, String detail) {}

    public record ActionResult(boolean ok, String message, List<String> details) {}

    /** Per-service health, so the page can show a green fleet before anything is broken. */
    @GetMapping(value = "/status", produces = MediaType.APPLICATION_JSON_VALUE)
    public List<ServiceStatus> status() {
        // Probed in parallel on virtual threads: eight sequential probes against a hung service
        // would take longer than the page's poll interval and make the UI look frozen.
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            Map<String, Future<ServiceStatus>> pending = new LinkedHashMap<>();
            fleet.forEach((name, url) -> pending.put(name, pool.submit(() -> probe(name, url))));

            List<ServiceStatus> out = new ArrayList<>(pending.size());
            pending.forEach((name, future) -> {
                try {
                    out.add(future.get());
                } catch (Exception e) {
                    out.add(new ServiceStatus(name, fleet.get(name), false, e.getMessage()));
                }
            });
            return out;
        }
    }

    private ServiceStatus probe(String name, String url) {
        try {
            String body = http.get().uri(url + "/actuator/health").retrieve().body(String.class);
            boolean up = body != null && body.contains("\"status\":\"UP\"");
            return new ServiceStatus(name, url, up, up ? "UP" : String.valueOf(body));
        } catch (RuntimeException e) {
            // A hung or erroring service is the normal case mid-demo, not an exceptional one.
            return new ServiceStatus(name, url, false, e.getClass().getSimpleName());
        }
    }

    /** Creates an availability and a latency SLO per fleet service. Safe to re-run. */
    @PostMapping(value = "/seed", produces = MediaType.APPLICATION_JSON_VALUE)
    public ActionResult seed() {
        List<String> details = new ArrayList<>();
        int created = 0;
        int existing = 0;

        for (String service : fleet.keySet()) {
            for (SloType type : SloType.values()) {
                try {
                    slos.create(new SloRequests.Create(
                            service,
                            type,
                            type == SloType.AVAILABILITY ? 0.999 : 0.99,
                            type == SloType.LATENCY ? LATENCY_THRESHOLD_MS : null,
                            ROLLING_WINDOW));
                    created++;
                } catch (RuntimeException e) {
                    // A duplicate target is the expected outcome of a re-run, not a failure.
                    existing++;
                    details.add(service + " " + type + ": already present");
                }
            }
        }

        String message = "%d SLOs created, %d already present".formatted(created, existing);
        log.info("demo seed: {}", message);
        return new ActionResult(true, message, details);
    }

    /**
     * Breaks one leaf so the failure climbs its whole call chain.
     *
     * @param services leaves to break; defaults to the order path's leaf
     */
    @PostMapping(value = "/chaos", produces = MediaType.APPLICATION_JSON_VALUE)
    public ActionResult chaos(@RequestParam(defaultValue = "ledger-service") List<String> services) {
        List<String> details = new ArrayList<>();
        boolean ok = true;

        for (String service : services) {
            String url = fleet.get(service);
            if (url == null) {
                details.add(service + ": not in the fleet");
                ok = false;
                continue;
            }
            try {
                post(url + "/chaos/errors?rate=" + CHAOS_ERROR_RATE);
                post(url + "/chaos/latency?ms=" + CHAOS_LATENCY_MS);
                details.add("%s: %.0f%% errors, +%dms".formatted(service, CHAOS_ERROR_RATE * 100, CHAOS_LATENCY_MS));
            } catch (RuntimeException e) {
                details.add(service + ": " + e.getMessage());
                ok = false;
            }
        }

        log.info("demo chaos on {}: ok={}", services, ok);
        return new ActionResult(
                ok, ok ? "Failure injected into " + String.join(", ", services) : "Some injections failed", details);
    }

    @PostMapping(value = "/reset", produces = MediaType.APPLICATION_JSON_VALUE)
    public ActionResult reset() {
        List<String> details = new ArrayList<>();
        boolean ok = true;

        for (Map.Entry<String, String> entry : fleet.entrySet()) {
            try {
                post(entry.getValue() + "/chaos/reset");
            } catch (RuntimeException e) {
                details.add(entry.getKey() + ": " + e.getMessage());
                ok = false;
            }
        }

        return new ActionResult(
                ok,
                ok
                        ? "Chaos cleared. Breaches keep firing for a few minutes while the error data ages out of the windows."
                        : "Some resets failed",
                details);
    }

    /**
     * Kills this process outright, so the demo can show an incident surviving it.
     *
     * <p>{@link Runtime#halt} rather than {@code System.exit}: exit runs shutdown hooks, drains the
     * Kafka listeners and closes the connection pool cleanly, which is a graceful stop and proves
     * nothing. Halt is the closest a process can get to killing itself the way {@code docker kill}
     * would — no hooks, no draining, offsets left uncommitted mid-flight.
     *
     * <p>Coming back is Docker's job: the compose file marks this service {@code restart:
     * unless-stopped}, so the container is restarted within a few seconds. What the demo then shows
     * is the point — incidents are still there, evaluation resumes, unacknowledged breaches are
     * redelivered, and the idempotency layers make that reprocessing a no-op rather than a pile of
     * duplicates.
     *
     * <p>Reachable only under the demo profile, for reasons that should not need spelling out.
     */
    @PostMapping(value = "/kill", produces = MediaType.APPLICATION_JSON_VALUE)
    public ActionResult kill() {
        log.warn("demo kill requested — halting the JVM; Docker's restart policy brings it back");

        // Delayed so this response is actually written before the process disappears. Without it
        // the browser sees a connection reset and reports a failure for something that worked.
        Thread.ofVirtual().name("demo-kill").start(() -> {
            try {
                Thread.sleep(Duration.ofMillis(400));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            Runtime.getRuntime().halt(137); // 128 + SIGKILL, the code docker kill produces.
        });

        return new ActionResult(
                true,
                "Killing Sentinel. Docker restarts it in a few seconds — incidents must survive, with no duplicates.",
                List.of());
    }

    private void post(String uri) {
        http.post().uri(uri).retrieve().toBodilessEntity();
    }
}
