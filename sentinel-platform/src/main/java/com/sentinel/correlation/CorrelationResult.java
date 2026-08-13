package com.sentinel.correlation;

import com.sentinel.events.SloBreachEvent;
import java.util.List;
import java.util.Set;

/**
 * What one breach correlated to.
 *
 * @param component the services breaching together in this window
 * @param originService earliest breach in the component, ties broken by graph depth
 * @param memberBreaches every in-window breach belonging to the component, oldest first
 */
public record CorrelationResult(Set<String> component, String originService, List<SloBreachEvent> memberBreaches) {

    /** The correlation key is the origin, frozen at incident creation and never recomputed onto it. */
    public String correlationKey() {
        return originService;
    }
}
