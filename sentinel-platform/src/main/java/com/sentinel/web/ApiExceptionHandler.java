package com.sentinel.web;

import com.sentinel.incident.IllegalStateTransitionException;
import com.sentinel.incident.IncidentNotFoundException;
import com.sentinel.slo.SloNotFoundException;
import com.sentinel.slo.SloValidationException;
import java.util.stream.Collectors;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** RFC 7807 responses. Spring Boot 3 provides ProblemDetail, so there is no custom error DTO. */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(SloValidationException.class)
    public ProblemDetail onValidation(SloValidationException e) {
        return problem(HttpStatus.BAD_REQUEST, "Invalid SLO definition", e.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail onBinding(MethodArgumentNotValidException e) {
        String detail = e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + " " + f.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return problem(HttpStatus.BAD_REQUEST, "Invalid request", detail);
    }

    @ExceptionHandler(SloNotFoundException.class)
    public ProblemDetail onNotFound(SloNotFoundException e) {
        return problem(HttpStatus.NOT_FOUND, "SLO not found", e.getMessage());
    }

    @ExceptionHandler(IncidentNotFoundException.class)
    public ProblemDetail onIncidentNotFound(IncidentNotFoundException e) {
        return problem(HttpStatus.NOT_FOUND, "Incident not found", e.getMessage());
    }

    /** 409 rather than 400: the request was well formed, the incident was not in that state. */
    @ExceptionHandler(IllegalStateTransitionException.class)
    public ProblemDetail onIllegalTransition(IllegalStateTransitionException e) {
        ProblemDetail problem = problem(HttpStatus.CONFLICT, "Illegal state transition", e.getMessage());
        problem.setProperty("from", e.from().name());
        problem.setProperty("to", e.to().name());
        problem.setProperty("allowed", e.from().allowedTargets());
        return problem;
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ProblemDetail onConflict(DataIntegrityViolationException e) {
        return problem(
                HttpStatus.CONFLICT,
                "Conflicts with an existing SLO",
                "an SLO already exists for that service, type and threshold");
    }

    private static ProblemDetail problem(HttpStatus status, String title, String detail) {
        ProblemDetail problem = ProblemDetail.forStatus(status);
        problem.setTitle(title);
        problem.setDetail(detail);
        return problem;
    }
}
