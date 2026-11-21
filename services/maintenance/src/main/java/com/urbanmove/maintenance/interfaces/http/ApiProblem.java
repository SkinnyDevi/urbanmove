package com.urbanmove.maintenance.interfaces.http;

import io.javalin.http.HttpStatus;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Error HTTP que se devolverá como RFC 7807. {@code extensions} son campos adicionales del
 * Problem, p. ej. {@code currentState} y {@code action} en una transición ilegal.
 */
public final class ApiProblem extends RuntimeException {

    /** Error de validación de un campo, para la lista {@code errors} de un 400. */
    public record FieldError(String field, String message) {
    }

    private final HttpStatus status;
    private final String type;
    private final Map<String, Object> extensions;

    public ApiProblem(HttpStatus status, String type, String detail, Map<String, Object> extensions) {
        super(detail);
        this.status = status;
        this.type = type;
        this.extensions = extensions == null ? Map.of() : new LinkedHashMap<>(extensions);
    }

    public ApiProblem(HttpStatus status, String type, String detail) {
        this(status, type, detail, null);
    }

    public static ApiProblem unauthorized() {
        return new ApiProblem(HttpStatus.UNAUTHORIZED, "/problems/unauthorized", "Missing or invalid JWT");
    }

    public static ApiProblem forbidden(String detail) {
        return new ApiProblem(HttpStatus.FORBIDDEN, "/problems/forbidden", detail);
    }

    public static ApiProblem forbidden() {
        return forbidden("Your role is not allowed to perform this operation");
    }

    public static ApiProblem validation(List<FieldError> errors) {
        return new ApiProblem(HttpStatus.BAD_REQUEST, "/problems/validation", "The request is not valid",
                Map.of("errors", List.copyOf(errors)));
    }

    public static ApiProblem validation(String field, String message) {
        return validation(List.of(new FieldError(field, message)));
    }

    public HttpStatus status() {
        return status;
    }

    public String type() {
        return type;
    }

    public Map<String, Object> extensions() {
        return extensions;
    }
}
