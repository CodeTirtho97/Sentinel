package com.sentinel.incident;

import com.sentinel.incident.api.IncidentResponses;
import com.sentinel.rca.RcaService;
import com.sentinel.slo.domain.Severity;
import jakarta.validation.Valid;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.task.TaskExecutionAutoConfiguration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.data.domain.PageRequest;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/incidents")
public class IncidentController {

    private static final Logger log = LoggerFactory.getLogger(IncidentController.class);

    private static final int MAX_PAGE_SIZE = 200;

    private final IncidentService service;
    private final RcaService rca;

    /** Boot's {@code applicationTaskExecutor}, which is virtual-thread backed under this config. */
    private final Executor executor;

    // Qualified by name: the scheduler is also a TaskExecutor, so the type alone is ambiguous.
    IncidentController(
            IncidentService service,
            RcaService rca,
            @Qualifier(TaskExecutionAutoConfiguration.APPLICATION_TASK_EXECUTOR_BEAN_NAME) TaskExecutor executor) {
        this.service = service;
        this.rca = rca;
        this.executor = executor;
    }

    @GetMapping
    public List<IncidentResponses.Summary> list(
            @RequestParam(required = false) IncidentState state,
            @RequestParam(required = false) Severity severity,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant since,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        int capped = Math.clamp(size, 1, MAX_PAGE_SIZE);
        return service.search(state, severity, since, PageRequest.of(Math.max(page, 0), capped)).stream()
                .map(IncidentResponses.Summary::from)
                .toList();
    }

    @GetMapping("/{id}")
    public IncidentResponses.Detail get(@PathVariable UUID id) {
        return IncidentResponses.Detail.from(service.get(id), service.timeline(id));
    }

    @PostMapping("/{id}/transition")
    public IncidentResponses.Summary transition(
            @PathVariable UUID id, @Valid @RequestBody IncidentResponses.TransitionRequest request) {
        return IncidentResponses.Summary.from(service.transition(id, request.to(), "api"));
    }

    /**
     * The drafted hypothesis.
     *
     * <p>202 while the RCA consumer is still working, 200 once there is something to read. A poller
     * can therefore tell "not written yet" from "written, and here it is" without inspecting the
     * body — which matters because a draft arrives seconds after the incident does.
     */
    @GetMapping("/{id}/rca")
    public ResponseEntity<IncidentResponses.Rca> rca(@PathVariable UUID id) {
        Incident incident = service.get(id);
        return incident.hasRca()
                ? ResponseEntity.ok(IncidentResponses.Rca.ready(incident))
                : ResponseEntity.accepted().body(IncidentResponses.Rca.pending(id));
    }

    /**
     * Redraft, discarding the existing draft.
     *
     * <p>Always 202: the redraft runs off the request thread because it may take ten seconds, and an
     * HTTP client should not be holding a connection open waiting on a language model.
     */
    @PostMapping("/{id}/rca:regenerate")
    public ResponseEntity<IncidentResponses.Rca> regenerateRca(@PathVariable UUID id) {
        // Resolves 404 now rather than on a background thread where nobody would see it.
        service.get(id);

        executor.execute(() -> {
            try {
                rca.draftFor(id, true);
            } catch (RuntimeException e) {
                log.warn("background RCA regeneration failed for incident {}: {}", id, e.toString());
            }
        });
        return ResponseEntity.accepted().body(IncidentResponses.Rca.pending(id));
    }
}
