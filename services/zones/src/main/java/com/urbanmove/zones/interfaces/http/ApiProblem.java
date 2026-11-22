package com.urbanmove.zones.interfaces.http;

import io.javalin.http.HttpStatus;

/** Error HTTP que se devolverá como RFC 7807. */
public final class ApiProblem extends RuntimeException {
    private final HttpStatus status;
    private final String type;

    public ApiProblem(HttpStatus status, String type, String detail) {
        super(detail);
        this.status = status;
        this.type = type;
    }

    public static ApiProblem unauthorized() {
        return new ApiProblem(HttpStatus.UNAUTHORIZED, "/problems/unauthorized", "Missing or invalid JWT");
    }

    public static ApiProblem forbidden() {
        return new ApiProblem(HttpStatus.FORBIDDEN, "/problems/forbidden",
                "Your role is not allowed to perform this operation");
    }

    public HttpStatus status() {
        return status;
    }

    public String type() {
        return type;
    }
}
