package com.sentinel.fleet;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

/** Blocking downstream calls, so a failure at the bottom of the graph cascades upward. */
@Component
public class DependencyCaller {

    private static final Logger log = LoggerFactory.getLogger(DependencyCaller.class);

    private final RestClient client;
    private final FleetProperties props;

    DependencyCaller(RestClient client, FleetProperties props) {
        this.client = client;
        this.props = props;
    }

    /** Calls every downstream in turn; the first failure aborts and propagates as a 502. */
    public void callAll() {
        for (String base : props.getDownstream()) {
            if (base == null || base.isBlank()) {
                continue;
            }
            try {
                client.get().uri(base.trim() + "/work").retrieve().toBodilessEntity();
            } catch (Exception e) {
                log.debug("downstream {} failed: {}", base, e.toString());
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "downstream failed: " + base, e);
            }
        }
    }
}
