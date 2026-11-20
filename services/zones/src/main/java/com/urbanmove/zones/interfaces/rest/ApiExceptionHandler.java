package com.urbanmove.zones.interfaces.rest;

import com.urbanmove.zones.application.ZoneNotFoundException;
import com.urbanmove.zones.domain.InvalidZoneException;
import com.urbanmove.zones.interfaces.web.Problems;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/** Convierte las excepciones en errores RFC 7807 con correlationId (AGENTS.md 3.1). */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(ZoneNotFoundException.class)
    ResponseEntity<ProblemDetail> notFound(ZoneNotFoundException e) {
        log.atInfo().addKeyValue("zoneId", e.zoneId().value()).log("Zone not found");
        return problem(HttpStatus.NOT_FOUND, "/problems/not-found", e.getMessage());
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<ProblemDetail> noRoute(NoResourceFoundException e) {
        return problem(HttpStatus.NOT_FOUND, "/problems/not-found", "No such resource");
    }

    @ExceptionHandler(InvalidZoneException.class)
    ResponseEntity<ProblemDetail> invalid(InvalidZoneException e) {
        return problem(HttpStatus.BAD_REQUEST, "/problems/validation", e.getMessage());
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<ProblemDetail> methodNotAllowed(HttpRequestMethodNotSupportedException e) {
        return problem(HttpStatus.METHOD_NOT_ALLOWED, "/problems/method-not-allowed", e.getMessage());
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> unexpected(Exception e) {
        log.error("Unexpected error", e);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "/problems/internal", "Unexpected error");
    }

    private static ResponseEntity<ProblemDetail> problem(HttpStatus status, String type, String detail) {
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(Problems.of(status, type, detail));
    }
}
