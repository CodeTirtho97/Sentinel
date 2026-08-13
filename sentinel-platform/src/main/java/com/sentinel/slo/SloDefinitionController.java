package com.sentinel.slo;

import com.sentinel.slo.api.SloRequests;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/slos")
public class SloDefinitionController {

    private final SloDefinitionService service;

    SloDefinitionController(SloDefinitionService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<SloRequests.Response> create(@Valid @RequestBody SloRequests.Create request) {
        var created = service.create(request);
        return ResponseEntity.created(URI.create("/api/v1/slos/" + created.id()))
                .body(SloRequests.Response.from(created));
    }

    @GetMapping
    public List<SloRequests.Response> list() {
        return service.list().stream().map(SloRequests.Response::from).toList();
    }

    @GetMapping("/{id}")
    public SloRequests.Response get(@PathVariable UUID id) {
        return SloRequests.Response.from(service.get(id));
    }

    @PatchMapping("/{id}")
    public SloRequests.Response patch(@PathVariable UUID id, @RequestBody SloRequests.Patch request) {
        return SloRequests.Response.from(service.patch(id, request));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{id}/budget")
    public SloRequests.Budget budget(@PathVariable UUID id) {
        return service.budget(id);
    }
}
